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
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

// Real local Hy2/QUIC, VLESS TLS+mux and Reality peers exercise reset/reconnect.
// This is not Android TUN, physical-network migration or a real-node test.
func TestSR01ProtocolsReconnectAfterReset(t *testing.T) {
	for _, protocol := range []string{"hysteria2", "vless-tls-mux", "vless-reality"} {
		t.Run(protocol, func(t *testing.T) {
			camouflage := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) }))
			camouflage.EnableHTTP2 = true
			camouflage.TLS = &tls.Config{MinVersion: tls.VersionTLS13}
			camouflage.StartTLS()
			defer camouflage.Close()
			certificate := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: camouflage.TLS.Certificates[0].Certificate[0]}))
			key, err := x509.MarshalPKCS8PrivateKey(camouflage.TLS.Certificates[0].PrivateKey)
			if err != nil {
				t.Fatal(err)
			}
			privateKey := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))
			network := "tcp"
			if protocol == "hysteria2" {
				network = "udp"
			}
			port := loopbackPort(t, network)
			const uuid = "00000000-0000-4000-8000-000000000001"
			server := map[string]any{"type": "vless", "listen": "127.0.0.1", "listen_port": port,
				"users": []any{map[string]any{"uuid": uuid}}, "tls": map[string]any{"enabled": true, "certificate": []string{certificate}, "key": []string{privateKey}}}
			node := map[string]any{"type": "vless", "tag": "node", "server": "127.0.0.1", "server_port": port, "uuid": uuid,
				"tls": map[string]any{"enabled": true, "server_name": "127.0.0.1", "certificate": []string{certificate}}}
			switch protocol {
			case "hysteria2":
				server["type"] = "hysteria2"
				server["users"] = []any{map[string]any{"password": "synthetic-reset-only"}}
				node["type"] = "hysteria2"
				delete(node, "uuid")
				node["password"] = "synthetic-reset-only"
			case "vless-tls-mux":
				server["multiplex"] = map[string]any{"enabled": true}
				node["multiplex"] = map[string]any{"enabled": true, "protocol": "smux"}
			case "vless-reality":
				private, err := ecdh.X25519().GenerateKey(rand.Reader)
				if err != nil {
					t.Fatal(err)
				}
				server["users"] = []any{map[string]any{"uuid": uuid, "flow": "xtls-rprx-vision"}}
				server["tls"] = map[string]any{"enabled": true, "server_name": "example.com", "reality": map[string]any{"enabled": true,
					"handshake":   map[string]any{"server": "127.0.0.1", "server_port": camouflage.Listener.Addr().(*net.TCPAddr).Port},
					"private_key": base64.RawURLEncoding.EncodeToString(private.Bytes()), "short_id": []string{"0123456789abcdef"}}}
				node["flow"] = "xtls-rprx-vision"
				node["tls"] = map[string]any{"enabled": true, "server_name": "example.com", "utls": map[string]any{"enabled": true, "fingerprint": "chrome"},
					"reality": map[string]any{"enabled": true, "public_key": base64.RawURLEncoding.EncodeToString(private.PublicKey().Bytes()), "short_id": "0123456789abcdef"}}
			}
			urlTestCore(t, map[string]any{"inbounds": []any{server}, "outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}, "route": map[string]any{"final": "direct"}}, nil)
			raw, err := json.Marshal(map[string]any{"log": map[string]any{"disabled": true}, "outbounds": []any{node}, "route": map[string]any{"auto_detect_interface": true, "final": "node"}})
			if err != nil {
				t.Fatal(err)
			}
			platform := new(regressionPlatform)
			b, err := newSingBoxInstance(string(raw), nil, platform)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { b.Close() })
			if err = b.Start(); err != nil {
				t.Fatal(err)
			}
			sr01Main(t, b)
			peer := sr01TCPPeer(t)
			var udpPeer net.PacketConn
			if protocol == "hysteria2" {
				udpPeer, err = net.ListenPacket("udp", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				defer udpPeer.Close()
				go func() {
					buffer := make([]byte, 64)
					for {
						n, from, e := udpPeer.ReadFrom(buffer)
						if e != nil {
							return
						}
						udpPeer.WriteTo(buffer[:n], from)
					}
				}()
			}
			echoUDP := func(conn net.PacketConn) error {
				conn.SetDeadline(time.Now().Add(time.Second))
				if _, e := conn.WriteTo([]byte("reset-check"), udpPeer.LocalAddr()); e != nil {
					return e
				}
				_, _, e := conn.ReadFrom(make([]byte, 64))
				return e
			}
			dialUDP := func() net.PacketConn {
				conn, e := b.Outbound().Default().ListenPacket(context.Background(), M.ParseSocksaddr(udpPeer.LocalAddr().String()))
				if e != nil {
					t.Fatal(e)
				}
				t.Cleanup(func() { conn.Close() })
				if e = echoUDP(conn); e != nil {
					t.Fatal("Hy2 datagram echo failed", e)
				}
				return conn
			}
			dial := func() net.Conn {
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				defer cancel()
				conn, err := b.Outbound().Default().DialContext(ctx, "tcp", M.ParseSocksaddr(peer.Addr().String()))
				if err != nil {
					t.Fatal(err)
				}
				t.Cleanup(func() { conn.Close() })
				if err = sr01Echo(conn); err != nil {
					t.Fatal("protocol echo failed", err)
				}
				return conn
			}
			for round := 0; round < 3; round++ {
				old := dial()
				var oldUDP net.PacketConn
				if udpPeer != nil {
					oldUDP = dialUDP()
				}
				before := platform.calls.Load()
				ResetAllConnections(true)
				if err = sr01Echo(old); err == nil {
					t.Fatal("old protocol connection remained usable")
				}
				if oldUDP != nil && echoUDP(oldUDP) == nil {
					t.Fatal("old Hy2 datagram session remained usable")
				}
				dial()
				if udpPeer != nil {
					dialUDP()
				}
				if platform.calls.Load() <= before {
					t.Fatal("reconnect reused old underlying transport without a fresh controlled socket")
				}
			}
		})
	}
}
