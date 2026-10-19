//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"context"
	stdtls "crypto/tls"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	btls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/transport/v2ray"
	qtls "github.com/sagernet/sing-quic"
	sjson "github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func cdFixture(t *testing.T, name string) ([]byte, map[string]json.RawMessage) {
	t.Helper()
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Fatal("Current JVM-generated CD fixtures are required")
	}
	data, err := os.ReadFile(filepath.Join(dir, "cd-"+name+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var root struct{ Outbounds []map[string]json.RawMessage }
	if err = json.Unmarshal(data, &root); err != nil || len(root.Outbounds) != 1 {
		t.Fatal("Invalid current fixture", err)
	}
	return data, root.Outbounds[0]
}

func TestProtocolCDGeneratedConfigurationsConstructRealCore(t *testing.T) {
	// Both normal inputs and retained legacy states use the real locked constructors.
	names := []string{"hy2", "hy2-legacy-alpn", "hy1-alpn", "anytls", "tcp-dynamic-ech",
		"ech-explicit-disabled", "manual-alpn-priority", "grpc-vless", "grpc-vmess", "grpc-trojan",
		"http-tcp", "http-ws", "http-http", "http-grpc", "http-httpupgrade",
		"static-ech-hysteria", "static-ech-hysteria2", "static-ech-tuic",
		"ssh-1-0", "ssh-2-0", "ssh-2-1", "ssh-0-1",
		"ssh-1-2", "ssh-0-2", "ssh-1-2-reopened", "ssh-0-2-reopened",
		"hy1-before-false-true", "hy1-after-false-true", "hy1-before-true-false", "hy1-after-true-false"}
	for _, p := range []string{"vless", "vmess", "trojan"} {
		for _, transport := range []string{"ws", "http", "grpc", "httpupgrade"} {
			names = append(names, p+"-"+transport)
		}
	}
	for _, name := range names {
		t.Run(name, func(t *testing.T) {
			data, _ := cdFixture(t, name)
			core, _ := modernTestCore(t, data)
			if err := core.Start(); err != nil {
				t.Fatal(err)
			}
			if strings.HasPrefix(name, "ssh-") {
				out, _ := core.Outbound().Outbound("proxy")
				actual := reflect.ValueOf(out).Elem().FieldByName("authMethod").Len()
				expected := 1
				if strings.HasSuffix(name, "-0") {
					expected = 0
				}
				if actual != expected {
					t.Fatalf("Actual SSH auth methods=%d expected=%d", actual, expected)
				}
			}
			if strings.HasPrefix(name, "hy1-") && !strings.HasSuffix(name, "alpn") {
				out, _ := core.Outbound().Outbound("proxy")
				client := reflect.ValueOf(out).Elem().FieldByName("client").Elem()
				q := client.FieldByName("quicConfig").Elem()
				actual := q.FieldByName("DisablePathMTUDiscovery").Bool()
				_, raw := cdFixture(t, name)
				var expected bool
				_ = json.Unmarshal(raw["disable_path_mtu_discovery"], &expected)
				if actual != expected {
					t.Fatal("Actual Hysteria client MTU differs from final generated setting")
				}
			}
		})
	}
}

func TestProtocolCDHysteria2RealHTTP3ALPN(t *testing.T) {
	_, raw := cdFixture(t, "hy2-legacy-alpn")
	var opts option.OutboundTLSOptions
	if err := sjson.Unmarshal(raw["tls"], &opts); err != nil {
		t.Fatal(err)
	}
	cfg, err := btls.NewClient(context.Background(), log.NewNOPFactory().Logger(), "example.invalid", opts)
	if err != nil {
		t.Fatal(err)
	}
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	var conn *quic.Conn
	rt, err := qtls.CreateTransport(left, &conn, cfg, &quic.Config{})
	if err != nil {
		t.Fatal(err)
	}
	h := rt.(*http3.Transport)
	defer h.Close()
	seen := make(chan []string, 1)
	h.Dial = func(_ context.Context, _ string, tls *stdtls.Config, _ *quic.Config) (*quic.Conn, error) {
		seen <- append([]string(nil), tls.NextProtos...)
		return nil, errors.New("synthetic stop before external networking")
	}
	req, _ := http.NewRequest("GET", "https://example.invalid/", nil)
	_, _ = h.RoundTrip(req)
	select {
	case alpn := <-seen:
		if !reflect.DeepEqual(alpn, []string{"h3"}) {
			t.Fatal(alpn)
		}
	case <-time.After(time.Second):
		t.Fatal("HTTP3 did not reach dial hook")
	}
}

func TestProtocolCDStaticECHIsActuallyInstalledForQUIC(t *testing.T) {
	for _, p := range []string{"hysteria", "hysteria2", "tuic"} {
		t.Run(p, func(t *testing.T) {
			_, raw := cdFixture(t, "static-ech-"+p)
			var opts option.OutboundTLSOptions
			if err := sjson.Unmarshal(raw["tls"], &opts); err != nil {
				t.Fatal(err)
			}
			cfg, err := btls.NewClient(context.Background(), log.NewNOPFactory().Logger(), "example.invalid", opts)
			if err != nil {
				t.Fatal(err)
			}
			if _, dynamic := cfg.(*btls.ECHClientConfig); dynamic {
				t.Fatal("Unexpected DNS-dependent ECH wrapper")
			}
			std, err := cfg.STDConfig()
			if err != nil {
				t.Fatal(err)
			}
			if len(std.EncryptedClientHelloConfigList) == 0 {
				t.Fatal("No static ECH installed in actual TLS config")
			}
		})
	}
}

func TestProtocolCDGRPCLiteActuallyUsesGeneratedTimeouts(t *testing.T) {
	for _, p := range []string{"vless", "vmess", "trojan"} {
		t.Run(p, func(t *testing.T) {
			_, raw := cdFixture(t, "grpc-"+p)
			var opts option.V2RayTransportOptions
			if err := sjson.Unmarshal(raw["transport"], &opts); err != nil {
				t.Fatal(err)
			}
			c, err := v2ray.NewClientTransport(context.Background(), N.SystemDialer, M.ParseSocksaddr("127.0.0.1:1"), opts, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			if reflect.TypeOf(c).String() != "*v2raygrpclite.Client" {
				t.Fatalf("Unexpected transport %T", c)
			}
			transport := reflect.ValueOf(c).Elem().FieldByName("transport").Elem()
			if transport.FieldByName("ReadIdleTimeout").Int() != int64(30*time.Second) ||
				transport.FieldByName("PingTimeout").Int() != int64(9*time.Second) {
				t.Fatal("Real HTTP2 timeout settings not applied")
			}
		})
	}
}
