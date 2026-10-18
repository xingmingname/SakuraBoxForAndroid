//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"

	"github.com/matsuridayo/libneko/speedtest"
	"github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

// These use real loopback protocol peers, TLS/Reality and the same HTTP client
// and RTT implementation as Libcore.UrlTest. OS socket protection is simulated;
// no Android device, remote node, public DNS service or TUN device is involved.
func urlTestCore(t *testing.T, config map[string]any, platform *regressionPlatform) (*box.Box, context.Context) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	registry := nekoboxAndroidInboundRegistry()
	hysteria2.RegisterInbound(registry)
	vless.RegisterInbound(registry)
	ctx = box.Context(ctx, registry, nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	if platform != nil {
		ctx = service.ContextWith[adapter.PlatformInterface](ctx, platform)
	}
	config["log"] = map[string]any{"level": "debug"}
	data, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	data, overrides, err := prepareConfig(string(data))
	if err != nil {
		t.Fatal(err)
	}
	var opts option.Options
	if err = opts.UnmarshalJSONContext(ctx, data); err != nil {
		t.Fatal(err)
	}
	logs := new(packetLog)
	t.Cleanup(func() {
		if t.Failed() {
			logs.Lock()
			defer logs.Unlock()
			for _, line := range logs.lines {
				t.Log(line)
			}
		}
	})
	b, err := box.New(box.Options{Context: ctx, Options: opts, PlatformLogWriter: logs})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	if err = applySniffOverrides(b.Router().Rules(), overrides); err != nil {
		t.Fatal(err)
	}
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	return b, ctx
}

func loopbackPort(t *testing.T, network string) int {
	t.Helper()
	if network == "udp" {
		c, e := net.ListenPacket("udp", "127.0.0.1:0")
		if e != nil {
			t.Fatal(e)
		}
		p := c.LocalAddr().(*net.UDPAddr).Port
		c.Close()
		return p
	}
	c, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	p := c.Addr().(*net.TCPAddr).Port
	c.Close()
	return p
}

func TestURLTestLiveProtocolsAndDNSBootstrap(t *testing.T) {
	// Mirror libcore/box.go's Android initialization in the isolated host package.
	previous := dialer.DoNotSelectInterface
	dialer.DoNotSelectInterface = true
	t.Cleanup(func() { dialer.DoNotSelectInterface = previous })
	for _, protocol := range []string{"hysteria2", "vless-tls", "vless-reality"} {
		for _, domain := range []bool{false, true} {
			name := protocol + "/ip-node"
			if domain {
				name = protocol + "/doh-domain-node"
			}
			t.Run(name, func(t *testing.T) {
				var hits, dnsHits atomic.Int32
				target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1); w.WriteHeader(http.StatusNoContent) }))
				defer target.Close()
				// This TLS endpoint supplies a generated test-only certificate,
				// serves DNS bootstrap and is the Reality camouflage handshake peer.
				doh := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					var request dns.Msg
					data, e := io.ReadAll(r.Body)
					if e != nil || request.Unpack(data) != nil || len(request.Question) != 1 {
						w.WriteHeader(400)
						return
					}
					dnsHits.Add(1)
					response := new(dns.Msg)
					response.SetReply(&request)
					if request.Question[0].Qtype == dns.TypeA {
						response.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: request.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 30}, A: net.IPv4(127, 0, 0, 1)}}
					}
					answer, e := response.Pack()
					if e != nil {
						w.WriteHeader(500)
						return
					}
					w.Header().Set("Content-Type", "application/dns-message")
					w.Write(answer)
				}))
				doh.EnableHTTP2 = true
				doh.TLS = &tls.Config{MinVersion: tls.VersionTLS13}
				doh.StartTLS()
				defer doh.Close()
				cert := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: doh.TLS.Certificates[0].Certificate[0]}))
				key, e := x509.MarshalPKCS8PrivateKey(doh.TLS.Certificates[0].PrivateKey)
				if e != nil {
					t.Fatal(e)
				}
				keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))
				dohPort := doh.Listener.Addr().(*net.TCPAddr).Port
				listenNetwork := "tcp"
				if protocol == "hysteria2" {
					listenNetwork = "udp"
				}
				port := loopbackPort(t, listenNetwork)
				serverTLS := map[string]any{"enabled": true, "certificate": []string{cert}, "key": []string{keyPEM}}
				clientTLS := map[string]any{"enabled": true, "server_name": "127.0.0.1", "certificate": []string{cert}}
				serverInbound := map[string]any{"type": "vless", "tag": "server", "listen": "127.0.0.1", "listen_port": port, "tls": serverTLS, "users": []any{map[string]any{"name": "synthetic", "uuid": "00000000-0000-4000-8000-000000000001"}}}
				serverAddress := "127.0.0.1"
				if domain {
					serverAddress = "node.invalid"
				}
				outbound := map[string]any{"type": "vless", "tag": "node", "server": serverAddress, "server_port": port, "uuid": "00000000-0000-4000-8000-000000000001", "tls": clientTLS, "domain_resolver": map[string]any{"server": "dns-direct", "strategy": "ipv4_only"}}
				if protocol == "hysteria2" {
					serverInbound["type"] = "hysteria2"
					serverInbound["users"] = []any{map[string]any{"name": "synthetic", "password": "local-test-only"}}
					outbound["type"] = "hysteria2"
					delete(outbound, "uuid")
					outbound["password"] = "local-test-only"
				} else if protocol == "vless-reality" {
					priv, e := ecdh.X25519().GenerateKey(rand.Reader)
					if e != nil {
						t.Fatal(e)
					}
					serverInbound["users"] = []any{map[string]any{"name": "synthetic", "uuid": "00000000-0000-4000-8000-000000000001", "flow": "xtls-rprx-vision"}}
					serverInbound["tls"] = map[string]any{"enabled": true, "server_name": "example.com", "reality": map[string]any{"enabled": true, "handshake": map[string]any{"server": "127.0.0.1", "server_port": dohPort}, "private_key": base64.RawURLEncoding.EncodeToString(priv.Bytes()), "short_id": []string{"0123456789abcdef"}}}
					outbound["flow"] = "xtls-rprx-vision"
					outbound["tls"] = map[string]any{"enabled": true, "server_name": "example.com", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"}, "reality": map[string]any{"enabled": true, "public_key": base64.RawURLEncoding.EncodeToString(priv.PublicKey().Bytes()), "short_id": "0123456789abcdef"}}
				}
				urlTestCore(t, map[string]any{"inbounds": []any{serverInbound}, "outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}, "route": map[string]any{"final": "direct"}}, nil)
				p := new(regressionPlatform)
				client, ctx := urlTestCore(t, map[string]any{"dns": map[string]any{"servers": []any{map[string]any{"type": "https", "tag": "dns-direct", "server": "127.0.0.1", "server_port": dohPort, "path": "/dns-query", "tls": map[string]any{"enabled": true, "server_name": "127.0.0.1", "certificate": []string{cert}}}}, "final": "dns-direct"}, "outbounds": []any{map[string]any{"type": "selector", "tag": "proxy", "outbounds": []string{"node"}, "default": "node"}, outbound, map[string]any{"type": "direct", "tag": "direct"}}, "route": map[string]any{"auto_detect_interface": true, "final": "proxy", "default_domain_resolver": map[string]any{"server": "dns-direct", "strategy": "ipv4_only"}}}, p)
				latency, err := speedtest.UrlTest(boxapi.CreateProxyHttpClient(client, nil), target.URL, 5000, speedtest.UrlTestStandard_RTT)
				if err != nil {
					t.Fatal(err)
				}
				if latency < 0 || hits.Load() != 2 || p.calls.Load() == 0 {
					t.Fatalf("live proxy URL Test failed: hits=%d control=%d", hits.Load(), p.calls.Load())
				}
				if domain && dnsHits.Load() == 0 {
					t.Fatal("domain-node test bypassed DNS bootstrap")
				}
				t.Log("URL Test: 2 HTTP requests via real " + protocol + " peer, selector=node, simulated protect calls=" + strconv.Itoa(int(p.calls.Load())) + ", bootstrap DNS=" + strconv.Itoa(int(dnsHits.Load())) + ", production monitor paused=" + strconv.FormatBool(service.FromContext[pause.Manager](ctx).IsNetworkPaused()))
			})
		}
	}
}
