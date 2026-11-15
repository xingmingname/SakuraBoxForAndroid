package nakis

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

const testUUID = "6c1c4a52-3b8e-4d9a-9a52-0d4f5f0f7a11"

func TestProofVector(t *testing.T) {
	id, _ := hex.DecodeString(strings.ReplaceAll(testUUID, "-", ""))
	var nonce [16]byte
	for i := range nonce {
		nonce[i] = byte(i)
	}
	bind := make([]byte, 32)
	for i := range bind {
		bind[i] = byte(160 + i)
	}
	vectors := []struct {
		bind []byte
		want string
	}{
		{nil, "2dc9e2e4f909f7c31baafb12d3e7051b24abee112bbbbf05033f12b876e5921e"},
		{bind, "09862ac857816c29057c084a9e823f7e290d4b08c17ac59d830b7080b6326997"},
	}
	for _, v := range vectors {
		proof := makeProofAt(id, v.bind, time.Unix(0, 1794000000000000000), nonce)
		if got := hex.EncodeToString(proof[24:]); got != v.want {
			t.Fatalf("proof MAC %s != %s", got, v.want)
		}
	}
	hello, err := HelloBytes("example.com", 443)
	if err != nil || hex.EncodeToString(hello) != "0101000b6578616d706c652e636f6d01bb" {
		t.Fatalf("Hello %x, %v", hello, err)
	}
}

func TestOptionPresenceAndLimits(t *testing.T) {
	var opts OutboundOptions
	err := json.Unmarshal([]byte(`{"server":"127.0.0.1","server_port":443,"uuid":"6c1c4a52-3b8e-4d9a-9a52-0d4f5f0f7a11","tls":false,"pool_max_uses":0,"send_tail":0,"send_quiet":0,"pool_hi":4096,"pool_max_age":"0","half_close_timeout":"7s","udp_jitter":"0s"}`), &opts)
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := resolveOptions(opts)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.tls || cfg.poolMaxUses != 0 || cfg.sendTail != 0 || cfg.sendQuiet != 0 || cfg.poolHi != 4096 || cfg.poolMaxAge != 15*time.Second || cfg.halfCloseTimeout != 7*time.Second || cfg.udpJitter != 0 {
		t.Fatalf("resolved option mismatch")
	}
	opts.UUID = "invalid"
	if err := ValidateOptions(opts); err == nil {
		t.Fatal("invalid secret accepted")
	}
	opts.UUID = testUUID
	opts.ShapeUp = [][2]int{{1, ShapeCap + 1}}
	if err := ValidateOptions(opts); err == nil {
		t.Fatal("invalid shape accepted")
	}
}

type loopDialer struct{ net.Dialer }

func (d loopDialer) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	return d.Dialer.DialContext(ctx, network, dst.String())
}
func (loopDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, net.UnknownNetworkError("udp")
}

func TestOfficialServerInterop(t *testing.T) {
	oracle := os.Getenv("NAKIS_ORACLE")
	if oracle == "" {
		t.Skip("set NAKIS_ORACLE to the pinned upstream executable")
	}
	target := listenTCP(t)
	defer target.Close()
	go func() {
		for {
			c, err := target.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()
	for _, mode := range []string{"plain", "ca", "pin", "ech", "bad-pin", "bad-ech", "bad-sni", "bad-uuid"} {
		t.Run(mode, func(t *testing.T) {
			path := t.TempDir()
			serverAddr := listenTCP(t)
			port := uint16(serverAddr.Addr().(*net.TCPAddr).Port)
			serverAddr.Close()
			server := map[string]any{"listen": net.JoinHostPort("127.0.0.1", strconv.Itoa(int(port))), "uuid": testUUID, "allow_private": true, "log_level": "error", "tls": mode != "plain"}
			useTLS := mode != "plain"
			opts := OutboundOptions{ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: port}, UUID: testUUID, TLS: &useTLS}
			if mode == "ca" {
				opts.ShapeUp = [][2]int{{90, 110}}
			}
			var roots *x509.CertPool
			if useTLS {
				runOracle(t, oracle, "cert", "-name", "nakis.test", "-out", path)
				server["tls_cert"] = filepath.Join(path, "cert.pem")
				server["tls_key"] = filepath.Join(path, "key.pem")
				opts.SNI = "nakis.test"
				pemData, err := os.ReadFile(server["tls_cert"].(string))
				if err != nil {
					t.Fatal(err)
				}
				block, _ := pem.Decode(pemData)
				cert, err := x509.ParseCertificate(block.Bytes)
				if err != nil {
					t.Fatal(err)
				}
				if mode == "ca" || mode == "bad-sni" {
					roots = x509.NewCertPool()
					roots.AddCert(cert)
				} else {
					pin := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
					if mode == "bad-pin" {
						pin[0] ^= 0xFF
					}
					opts.TLSPins = []string{hex.EncodeToString(pin[:])}
				}
			}
			if mode == "ech" || mode == "bad-ech" {
				out := runOracle(t, oracle, "ech", "-public-name", "public.nakis.test")
				before, after, ok := strings.Cut(strings.ReplaceAll(string(out), "\r\n", "\n"), "\n\nclient.json:\n")
				if !ok {
					t.Fatal("unexpected official ECH fixture output")
				}
				if mode == "ech" {
					server["ech_keys"] = json.RawMessage(strings.TrimPrefix(before, "server.json:\n  \"ech_keys\": "))
				}
				var client map[string]string
				if err := json.Unmarshal([]byte("{"+strings.TrimSpace(after)+"}"), &client); err != nil {
					t.Fatal(err)
				}
				opts.ECHConfig = client["ech_config"]
			}
			if mode == "bad-sni" {
				opts.SNI = "wrong.nakis.test"
			}
			if mode == "bad-uuid" {
				opts.UUID = "ffffffffffffffffffffffffffffffff"
			}
			serverPath := filepath.Join(path, "server.json")
			data, err := json.Marshal(server)
			if err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(serverPath, data, 0600); err != nil {
				t.Fatal(err)
			}
			cmd := exec.Command(oracle, "server", "-c", serverPath)
			if err = cmd.Start(); err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
			until(t, func() bool {
				c, err := net.DialTimeout("tcp", server["listen"].(string), 50*time.Millisecond)
				if err != nil {
					return false
				}
				c.Close()
				return true
			})
			cfg, err := resolveOptions(opts)
			if err != nil {
				t.Fatal(err)
			}
			tlsCfg := clientTLS(context.Background(), cfg)
			if roots != nil {
				tlsCfg.RootCAs = roots
			}
			p := newPool(context.Background(), cfg, loopDialer{}, tlsCfg)
			t.Cleanup(p.close)
			p.warming = true // Isolate NAP reuse from independently tested prewarm.
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
			defer cancel()
			dst := M.ParseSocksaddr(target.Addr().String())
			raw, err := p.dial(ctx, dst)
			if mode == "bad-pin" || mode == "bad-ech" || mode == "bad-sni" {
				if err == nil {
					raw.Close()
					t.Fatal("invalid TLS accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if mode == "plain" {
				testOfficialProofRejection(t, server["listen"].(string), cfg.uuid, dst)
			}
			session1 := raw.(*session)
			if mode == "bad-uuid" {
				_ = session1.SetDeadline(time.Now().Add(2 * time.Second))
				if _, err = session1.Read(make([]byte, 1)); err == nil {
					t.Fatal("invalid UUID accepted")
				}
				session1.Close()
				return
			}
			for i := 0; i < 2; i++ {
				if i == 1 {
					until(t, func() bool { p.mu.Lock(); defer p.mu.Unlock(); return len(p.idle) > 0 })
					raw, err = p.dial(ctx, dst)
					if err != nil {
						t.Fatal(err)
					}
					if raw.(*session).t != session1.t {
						t.Fatal("tunnel was not reused")
					}
				}
				echo(t, raw, []byte("official upstream echo"))
				if err = raw.Close(); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
}

func listenTCP(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	return ln
}

func until(t *testing.T, ready func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !ready() {
		if time.Now().After(deadline) {
			t.Fatal("timed out waiting for test condition")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func runOracle(t *testing.T, path string, args ...string) []byte {
	t.Helper()
	out, err := exec.Command(path, args...).CombinedOutput()
	if err != nil {
		t.Fatalf("official %s failed: %v", args[0], err)
	}
	return out
}

func echo(t *testing.T, c net.Conn, body []byte) {
	t.Helper()
	if err := c.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Write(body); err != nil {
		t.Fatal(err)
	}
	out := make([]byte, len(body))
	if _, err := io.ReadFull(c, out); err != nil || !bytes.Equal(body, out) {
		t.Fatalf("echo %q, %v", out, err)
	}
}

func TestPoolCloseInterruptsActiveReadAndPrewarm(t *testing.T) {
	ln := listenTCP(t)
	defer ln.Close()
	accepts := make(chan net.Conn, 4)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			select {
			case accepts <- c:
			default:
				c.Close()
			}
		}
	}()
	addr := ln.Addr().(*net.TCPAddr)
	plain := false
	cfg, err := resolveOptions(OutboundOptions{ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: uint16(addr.Port)}, UUID: testUUID, TLS: &plain})
	if err != nil {
		t.Fatal(err)
	}
	p := newPool(context.Background(), cfg, loopDialer{}, nil)
	defer p.close()
	c, err := p.dial(context.Background(), M.ParseSocksaddr("example.com:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	for range 2 {
		select {
		case conn := <-accepts:
			defer conn.Close()
		case <-time.After(3 * time.Second):
			t.Fatal("prewarm did not open a socket")
		}
	}
	read := make(chan error, 1)
	go func() { _, err := c.Read(make([]byte, 1)); read <- err }()
	p.close()
	select {
	case err = <-read:
		if err == nil {
			t.Fatal("active read unexpectedly succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("active read was not interrupted")
	}
	if len(p.live) != 0 {
		t.Fatalf("%d live tunnels left", len(p.live))
	}
	if _, err = p.dial(context.Background(), M.ParseSocksaddr("example.com:443")); !errors.Is(err, errClosed) {
		t.Fatalf("dial after close: %v", err)
	}
}

func testOfficialProofRejection(t *testing.T, addr string, key []byte, dst M.Socksaddr) {
	t.Helper()
	hello, err := HelloBytes(dst.AddrString(), dst.Port)
	if err != nil {
		t.Fatal(err)
	}
	makeClient := func(proof []byte, accepted bool) {
		t.Helper()
		conn, err := net.DialTimeout("tcp", addr, time.Second)
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		if err := conn.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
			t.Fatal(err)
		}
		wire := WrapTunnel(conn, nil)
		payload := []byte("proof echo")
		if err := wire.Send(append(bytes.Clone(proof), hello...), payload); err != nil {
			t.Fatal(err)
		}
		err = wire.ReadReply()
		if !accepted {
			var timeout net.Error
			if err == nil || errors.As(err, &timeout) && timeout.Timeout() {
				t.Fatalf("expected active rejection, got %v", err)
			}
			return
		}
		out := make([]byte, len(payload))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := io.ReadFull(wire, out); err != nil || !bytes.Equal(payload, out) {
			t.Fatalf("proof echo %q: %v", out, err)
		}
	}
	now := time.Now()
	proof := makeProofAt(key, nil, now, randBytes())
	makeClient(proof, true)
	makeClient(proof, false)
	makeClient(makeProofAt(key, nil, now.Add(-2*time.Minute), randBytes()), false)
}

func TestOfficialClientRangesAndDefaults(t *testing.T) {
	var opts OutboundOptions
	err := json.Unmarshal([]byte("{\"server\":\"127.0.0.1\",\"server_port\":443,\"uuid\":\""+testUUID+"\",\"sni\":\"example.test\"}"), &opts)
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := resolveOptions(opts)
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.tls || cfg.poolLo != 2 || cfg.poolHi != 32 || cfg.poolMaxAge != 45*time.Second ||
		cfg.poolMaxUses != 8 || cfg.raceCount != 3 || cfg.dialTimeout != 8*time.Second ||
		cfg.handshakeTimeout != 10*time.Second || cfg.keepalive != 15*time.Second ||
		cfg.sendPack != 20480 || cfg.sendTail != 512 || cfg.sendQuiet != 4 ||
		cfg.shapeWait != 20*time.Millisecond || cfg.halfCloseTimeout != 120*time.Second || cfg.udpJitter != 8*time.Millisecond {
		t.Fatalf("defaults differ from upstream: %+v", cfg)
	}

	err = json.Unmarshal([]byte(`{"race_count":12,"pool_lo":1024,"pool_hi":4096,"pool_max_age":"3h","keepalive":"2h","dial_timeout":"3m","handshake_timeout":"4m","shape_wait":"2s","udp_jitter":"500ms"}`), &opts)
	if err != nil {
		t.Fatal(err)
	}
	opts.UUID = strings.Repeat("-", 128) + testUUID
	for i := range 40 {
		opts.ServerAlternates = append(opts.ServerAlternates, fmt.Sprintf("alt%d.example.test:443", i))
		opts.TLSPins = append(opts.TLSPins, fmt.Sprintf("%064x", i))
	}
	cfg, err = resolveOptions(opts)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.poolLo != 1024 || cfg.poolHi != 4096 || cfg.raceCount != 12 ||
		cfg.poolMaxAge != 3*time.Hour || cfg.keepalive != 2*time.Hour ||
		cfg.dialTimeout != 3*time.Minute || cfg.handshakeTimeout != 4*time.Minute ||
		cfg.shapeWait != 2*time.Second || cfg.udpJitter != 200*time.Millisecond ||
		len(cfg.pins) != 40 || len(cfg.servers) != 41 {
		t.Fatalf("valid official values changed: %+v", cfg)
	}
}
