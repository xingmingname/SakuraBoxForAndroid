//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/trojan"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing-box/protocol/vmess"
	"github.com/sagernet/sing-box/transport/v2ray"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

func TestV2RayQUICGeneratedSecurityMatrix(t *testing.T) {
	files, err := filepath.Glob(filepath.Join(os.Getenv("NEKO_CONFIG_FIXTURES"), "aud008", "aud008-*.json"))
	if err != nil || len(files) != 72 {
		t.Fatalf("Need 72 current JVM-generated fixtures, got %d: %v", len(files), err)
	}
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			data, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			name := strings.TrimSuffix(filepath.Base(file), ".json")
			if strings.HasSuffix(name, "-quic-none") {
				_, err = v2ray.NewClientTransport(context.Background(), nil, M.ParseSocksaddr("127.0.0.1:443"), option.V2RayTransportOptions{Type: "quic"}, nil)
				if err == nil || !strings.Contains(err.Error(), "TLS") {
					t.Fatalf("missing TLS not rejected: %v", err)
				}
				t.Logf("Actual transport rejects missing TLS: %v", err)
				return
			}
			core, ctx := modernTestCore(t, data)
			if err = core.Start(); err != nil {
				t.Fatal(err)
			}
			if strings.HasSuffix(name, "-quic-utls") || strings.HasSuffix(name, "-quic-reality") {
				outbound, _ := core.Outbound().Outbound("node")
				ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
				defer cancel()
				conn, err := outbound.DialContext(ctx, "tcp", M.ParseSocksaddr("127.0.0.1:9"))
				if conn != nil {
					conn.Close()
				}
				if err == nil || !strings.Contains(err.Error(), "unsupported usage") {
					t.Fatalf("unexpected actual dial result: %v", err)
				}
				t.Logf("Actual outbound dial rejects incompatible TLS engine: %v", err)
			}
		})
	}
}

func aud008ProtocolPeer(t *testing.T, config map[string]any) *box.Box {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	registry := nekoboxAndroidInboundRegistry()
	vless.RegisterInbound(registry)
	vmess.RegisterInbound(registry)
	trojan.RegisterInbound(registry)
	ctx = box.Context(ctx, registry, nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, raw); err != nil {
		t.Fatal(err)
	}
	peer, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { peer.Close() })
	if err = peer.Start(); err != nil {
		t.Fatal(err)
	}
	return peer
}

func TestV2RayQUICStandardTLSLoopbackTraffic(t *testing.T) {
	tlsPeer := httptest.NewTLSServer(http.NotFoundHandler())
	defer tlsPeer.Close()
	cert := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: tlsPeer.TLS.Certificates[0].Certificate[0]}))
	key, err := x509.MarshalPKCS8PrivateKey(tlsPeer.TLS.Certificates[0].PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))
	for _, protocol := range []string{"vless", "vmess", "trojan"} {
		t.Run(protocol, func(t *testing.T) {
			raw, err := os.ReadFile(filepath.Join(os.Getenv("NEKO_CONFIG_FIXTURES"), "aud008", "aud008-"+protocol+"-quic-tls.json"))
			if err != nil {
				t.Fatal(err)
			}
			var config map[string]any
			if err = json.Unmarshal(raw, &config); err != nil {
				t.Fatal(err)
			}
			node := config["outbounds"].([]any)[0].(map[string]any)
			port := loopbackPort(t, "udp")
			inbound := map[string]any{"type": protocol, "tag": "peer", "listen": "127.0.0.1", "listen_port": port, "transport": map[string]any{"type": "quic"}, "tls": map[string]any{"enabled": true, "certificate": []string{cert}, "key": []string{keyPEM}}}
			if protocol == "trojan" {
				inbound["users"] = []any{map[string]any{"name": "synthetic", "password": node["password"]}}
			} else {
				inbound["users"] = []any{map[string]any{"name": "synthetic", "uuid": node["uuid"]}}
			}
			aud008ProtocolPeer(t, map[string]any{"inbounds": []any{inbound}, "outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}})
			// Only ephemeral loopback endpoint/certificate information is substituted.
			node["server_port"] = port
			node["tls"].(map[string]any)["server_name"] = "127.0.0.1"
			node["tls"].(map[string]any)["certificate"] = []string{cert}
			data, _ := json.Marshal(config)
			core, ctx := modernTestCore(t, data)
			if err = core.Start(); err != nil {
				t.Fatal(err)
			}
			outbound, _ := core.Outbound().Outbound("node")
			target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("aud008-quic-tls-ok")) }))
			defer target.Close()
			transport := &http.Transport{DialContext: func(c context.Context, network, address string) (net.Conn, error) {
				return outbound.DialContext(c, network, M.ParseSocksaddr(address))
			}}
			defer transport.CloseIdleConnections()
			client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
			request, _ := http.NewRequestWithContext(ctx, "GET", target.URL, nil)
			response, err := client.Do(request)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			body, err := io.ReadAll(response.Body)
			if err != nil || string(body) != "aud008-quic-tls-ok" {
				t.Fatalf("bad local protocol response: %q %v", body, err)
			}
			t.Log("Real protocol over QUIC + verified standard TLS carried loopback HTTP traffic")
		})
	}
}
