//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/anytls"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

func TestProtocolUIFinalJVMConfigurations(t *testing.T) {
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Skip("Current JVM serialized fixtures required")
	}
	files, err := filepath.Glob(filepath.Join(dir, "protocol-ui-*.json"))
	if err != nil || len(files) < 7 {
		t.Fatal("Missing current protocol UI fixtures")
	}
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			data, e := os.ReadFile(file)
			if e != nil {
				t.Fatal(e)
			}
			b, _ := modernTestCore(t, data) // Actual migration, strict Options, New.
			if e = b.Start(); e != nil {
				t.Fatal(e)
			}
			if _, e = ExportPortableConfig(string(data)); e != nil {
				t.Fatal("Portable export rejected final generated configuration:", e)
			}
		})
	}
}

func protocolUIAnyTLSPeer(t *testing.T, config map[string]any) *box.Box {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	registry := nekoboxAndroidInboundRegistry()
	anytls.RegisterInbound(registry)
	ctx = box.Context(ctx, registry, nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	data, e := json.Marshal(config)
	if e != nil {
		t.Fatal(e)
	}
	var options option.Options
	if e = options.UnmarshalJSONContext(ctx, data); e != nil {
		t.Fatal(e)
	}
	b, e := box.New(box.Options{Context: ctx, Options: options})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { b.Close() })
	if e = b.Start(); e != nil {
		t.Fatal(e)
	}
	return b
}

func TestProtocolUIAnyTLSGeneratedTCPUDPAndAuthentication(t *testing.T) {
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Skip("Current JVM serialized AnyTLS fixture required")
	}
	data, e := os.ReadFile(filepath.Join(dir, "protocol-ui-anytls-idle.json"))
	if e != nil {
		t.Fatal(e)
	}
	var config map[string]any
	if e = json.Unmarshal(data, &config); e != nil {
		t.Fatal(e)
	}
	previous := dialer.DoNotSelectInterface
	dialer.DoNotSelectInterface = true
	t.Cleanup(func() { dialer.DoNotSelectInterface = previous })
	// Locally generated ephemeral test credentials; never a real user certificate or node.
	target := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("synthetic-anytls-ok")) }))
	defer target.Close()
	cert := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: target.TLS.Certificates[0].Certificate[0]}))
	key, e := x509.MarshalPKCS8PrivateKey(target.TLS.Certificates[0].PrivateKey)
	if e != nil {
		t.Fatal(e)
	}
	keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))
	port := loopbackPort(t, "tcp")
	protocolUIAnyTLSPeer(t, map[string]any{
		"inbounds": []any{map[string]any{"type": "anytls", "tag": "peer", "listen": "127.0.0.1", "listen_port": port,
			"users": []any{map[string]any{"name": "synthetic", "password": "synthetic-test-password"}},
			"tls":   map[string]any{"enabled": true, "certificate": []string{cert}, "key": []string{keyPEM}}}},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}},
		"route":     map[string]any{"final": "direct"},
	})
	out := config["outbounds"].([]any)[0].(map[string]any)
	out["server_port"] = port
	tlsOptions := out["tls"].(map[string]any)
	tlsOptions["server_name"] = "127.0.0.1"
	tlsOptions["certificate"] = []string{cert}
	encoded, _ := json.Marshal(config)
	b, _ := modernTestCore(t, encoded)
	if e = b.Start(); e != nil {
		t.Fatal(e)
	}
	node, ok := b.Outbound().Outbound("proxy")
	if !ok {
		t.Fatal("Missing generated proxy outbound")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	t.Run("TCP_TLS", func(t *testing.T) {
		// Send HTTP to a loopback HTTP endpoint through the real AnyTLS TLS peer.
		httpTarget := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("synthetic-anytls-ok")) }))
		defer httpTarget.Close()
		c, e := node.DialContext(ctx, "tcp", M.ParseSocksaddr(httpTarget.Listener.Addr().String()))
		if e != nil {
			t.Fatal(e)
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(5 * time.Second))
		if _, e = io.WriteString(c, "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n"); e != nil {
			t.Fatal(e)
		}
		httpResponse, e := http.ReadResponse(bufio.NewReader(c), &http.Request{Method: "GET"})
		if e != nil {
			t.Fatal(e)
		}
		defer httpResponse.Body.Close()
		response, e := io.ReadAll(httpResponse.Body)
		if e != nil {
			t.Fatal(e)
		}
		if httpResponse.StatusCode != 200 {
			t.Fatal("Unexpected HTTP status", httpResponse.StatusCode)
		}
		if !bytes.Contains(response, []byte("synthetic-anytls-ok")) {
			t.Fatal("No response through actual AnyTLS peer")
		}
	})
	t.Run("UDP_over_AnyTLS", func(t *testing.T) {
		echo, e := net.ListenPacket("udp", "127.0.0.1:0")
		if e != nil {
			t.Fatal(e)
		}
		defer echo.Close()
		go func() {
			buf := make([]byte, 64)
			n, addr, err := echo.ReadFrom(buf)
			if err == nil {
				echo.WriteTo(buf[:n], addr)
			}
		}()
		c, e := node.ListenPacket(ctx, M.ParseSocksaddr(echo.LocalAddr().String()))
		if e != nil {
			t.Fatal(e)
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(5 * time.Second))
		if _, e = c.WriteTo([]byte("synthetic-udp"), echo.LocalAddr()); e != nil {
			t.Fatal(e)
		}
		buf := make([]byte, 64)
		n, _, e := c.ReadFrom(buf)
		if e != nil {
			t.Fatal(e)
		}
		if string(buf[:n]) != "synthetic-udp" {
			t.Fatal("UDP mismatch")
		}
	})
	for _, correct := range []bool{true, false} {
		name := "correct_public_key_pin"
		if !correct {
			name = "wrong_public_key_pin"
		}
		t.Run(name, func(t *testing.T) {
			var pinConfig map[string]any
			if err := json.Unmarshal(encoded, &pinConfig); err != nil {
				t.Fatal(err)
			}
			pinTLS := pinConfig["outbounds"].([]any)[0].(map[string]any)["tls"].(map[string]any)
			delete(pinTLS, "certificate")
			pin := sha256.Sum256(target.Certificate().RawSubjectPublicKeyInfo)
			if !correct {
				pin[0] ^= 0xff
			}
			pinTLS["certificate_public_key_sha256"] = []string{base64.StdEncoding.EncodeToString(pin[:])}
			pinData, _ := json.Marshal(pinConfig)
			pinBox, _ := modernTestCore(t, pinData)
			if err := pinBox.Start(); err != nil {
				t.Fatal(err)
			}
			pinNode, ok := pinBox.Outbound().Outbound("proxy")
			if !ok {
				t.Fatal("Missing pin-test node")
			}
			httpTarget := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("pin-verified")) }))
			defer httpTarget.Close()
			pinCtx, pinCancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer pinCancel()
			conn, err := pinNode.DialContext(pinCtx, "tcp", M.ParseSocksaddr(httpTarget.Listener.Addr().String()))
			if err != nil {
				if correct {
					t.Fatal(err)
				}
				return
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(3 * time.Second))
			_, err = io.WriteString(conn, "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n")
			if err != nil {
				if correct {
					t.Fatal(err)
				}
				return
			}
			httpResponse, err := http.ReadResponse(bufio.NewReader(conn), &http.Request{Method: "GET"})
			if err != nil {
				if correct {
					t.Fatal(err)
				}
				return
			}
			defer httpResponse.Body.Close()
			response, err := io.ReadAll(httpResponse.Body)
			if correct && (err != nil || httpResponse.StatusCode != 200 || !bytes.Contains(response, []byte("pin-verified"))) {
				t.Fatal("Correct pin did not forward request", err)
			}
			if !correct {
				t.Fatal("Wrong public-key pin was accepted")
			}
		})
	}
	t.Run("authentication_failure", func(t *testing.T) {
		out["password"] = "synthetic-wrong-password"
		badData, _ := json.Marshal(config)
		bad, _ := modernTestCore(t, badData)
		if err := bad.Start(); err != nil {
			t.Fatal(err)
		}
		badNode, ok := bad.Outbound().Outbound("proxy")
		if !ok {
			t.Fatal("Missing bad-password node")
		}
		badCtx, badCancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer badCancel()
		conn, err := badNode.DialContext(badCtx, "tcp", M.ParseSocksaddr(target.Listener.Addr().String()))
		if err != nil {
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(3 * time.Second))
		_, writeErr := io.WriteString(conn, "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n")
		buf := make([]byte, 64)
		n, readErr := conn.Read(buf)
		if writeErr == nil && readErr == nil && n > 0 {
			t.Fatal("Invalid password unexpectedly forwarded data")
		}
	})
}
