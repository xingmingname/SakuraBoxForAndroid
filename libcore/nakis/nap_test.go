package nakis

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

func testTLSCertificate(t *testing.T) tls.Certificate {
	t.Helper()
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	cert := &x509.Certificate{
		SerialNumber: big.NewInt(1), DNSNames: []string{"nakis.test"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, pub, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

type trailingTLSRecord struct {
	net.Conn
	tail []byte
}

func (c *trailingTLSRecord) Write(p []byte) (int, error) {
	if len(c.tail) == 0 {
		return c.Conn.Write(p)
	}
	data := append(bytes.Clone(p), c.tail...)
	c.tail = nil
	n, err := c.Conn.Write(data)
	return min(n, len(p)), err
}

func TestNAPIsolation(t *testing.T) {
	for _, mode := range []string{"plain-clean", "plain-trailing", "tls-clean", "tls-same-record", "tls-next-record", "tls-partial-record", "tls-delayed-nap", "plain-one-use", "plain-byte-budget", "pad-between-naps", "missing-nap"} {
		t.Run(mode, func(t *testing.T) {
			useTLS := mode[:3] == "tls"
			clean := mode == "plain-clean" || mode == "tls-clean" || mode == "tls-delayed-nap" || mode == "pad-between-naps"
			listener := listenTCP(t)
			t.Cleanup(func() { _ = listener.Close() })
			var client *tls.Config
			var server *tls.Config
			port := uint16(listener.Addr().(*net.TCPAddr).Port)
			opts := OutboundOptions{ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: port}, UUID: testUUID, TLS: &useTLS}
			switch mode {
			case "plain-one-use":
				uses := 1
				opts.PoolMaxUses = &uses
			case "plain-byte-budget":
				uses := 0
				opts.PoolMaxUses = &uses
			}
			if useTLS {
				cert := testTLSCertificate(t)
				leaf, err := x509.ParseCertificate(cert.Certificate[0])
				if err != nil {
					t.Fatal(err)
				}
				opts.TLSPins = []string{fmt.Sprintf("%x", sha256.Sum256(leaf.RawSubjectPublicKeyInfo))}
				server = &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13}
			}
			cfg, err := resolveOptions(opts)
			if err != nil {
				t.Fatal(err)
			}
			if useTLS {
				client = clientTLS(context.Background(), cfg)
			}
			p := newPool(context.Background(), cfg, loopDialer{}, client)
			t.Cleanup(p.close)
			p.warming = true
			dst := M.ParseSocksaddr("example.com:443")
			hello, err := HelloBytes(dst.AddrString(), dst.Port)
			if err != nil {
				t.Fatal(err)
			}
			var accepted atomic.Int32
			peer := make(chan error, 2)
			go func() {
				for {
					conn, err := listener.Accept()
					if err != nil {
						return
					}
					number := accepted.Add(1)
					go func() {
						defer conn.Close()
						peer <- napTestPeer(conn, number, server, mode, hello)
					}()
				}
			}()
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
			defer cancel()
			dial := func() *session {
				t.Helper()
				conn, err := p.dial(ctx, dst)
				if err != nil {
					t.Fatal(err)
				}
				if err := conn.SetDeadline(time.Now().Add(time.Second)); err != nil {
					t.Fatal(err)
				}
				return conn.(*session)
			}
			read := func(c net.Conn, want byte) {
				t.Helper()
				out := []byte{0}
				if _, err := io.ReadFull(c, out); err != nil || out[0] != want {
					t.Fatalf("response %q, want %q: %v", out, want, err)
				}
			}
			first := dial()
			read(first, 'a')
			switch mode {
			case "tls-delayed-nap":
				p.handshakeAvg.Store(int64(400 * time.Millisecond))
			case "plain-byte-budget":
				first.t.wire.noteMoved(maxLifeBytes)
			}
			_ = first.Close()
			until(t, func() bool {
				p.mu.Lock()
				defer p.mu.Unlock()
				return len(p.live) == 0 || len(p.idle) > 0
			})
			second := dial()
			defer second.Close()
			if (second.t == first.t) != clean {
				t.Fatalf("reused=%v, want %v", second.t == first.t, clean)
			}
			read(second, 'b')
			wantSockets := int32(2)
			if clean {
				wantSockets = 1
			}
			if got := accepted.Load(); got != wantSockets {
				t.Fatalf("physical sockets=%d, want %d", got, wantSockets)
			}
			select {
			case err := <-peer:
				if err != nil {
					t.Fatal(err)
				}
			default:
			}
		})
	}
}

func napTestPeer(raw net.Conn, number int32, cfg *tls.Config, mode string, hello []byte) error {
	_ = raw.SetDeadline(time.Now().Add(3 * time.Second))
	underlay := &trailingTLSRecord{Conn: raw}
	var conn net.Conn = raw
	if cfg != nil {
		conn = tls.Server(underlay, cfg)
	}
	wire := WrapTunnel(conn, nil)
	readHello := func(fresh bool) error {
		n := len(hello)
		if fresh {
			n += proofSize
		}
		data := make([]byte, n)
		if _, err := io.ReadFull(wire, data); err != nil {
			return err
		}
		if !bytes.Equal(data[n-len(hello):], hello) {
			return errors.New("peer received an unexpected Hello")
		}
		return nil
	}
	if err := readHello(true); err != nil {
		return err
	}
	if number > 1 {
		return wire.Send(CozyByte, []byte{'b'})
	}
	if err := wire.Send(CozyByte, []byte{'a'}); err != nil {
		return err
	}
	if _, err := wire.Read(make([]byte, 1)); !errors.Is(err, ErrNap) {
		return fmt.Errorf("expected NAP: %w", err)
	}
	switch mode {
	case "plain-clean", "tls-clean", "tls-delayed-nap":
		if mode == "tls-delayed-nap" {
			time.Sleep(600 * time.Millisecond)
		}
		if err := wire.Nap(); err != nil {
			return err
		}
		if err := readHello(false); err != nil {
			return err
		}
		return wire.Send(CozyByte, []byte{'b'})
	case "plain-one-use", "plain-byte-budget":
		if err := wire.Nap(); err != nil {
			return err
		}
	case "plain-trailing", "tls-same-record":
		if _, err := conn.Write([]byte{0, 0, 0, 2, RespCozy, 'x'}); err != nil {
			return err
		}
	case "tls-next-record":
		if err := wire.Nap(); err != nil {
			return err
		}
		if err := wire.Send(CozyByte, []byte{'x'}); err != nil {
			return err
		}
	case "tls-partial-record":
		underlay.tail = []byte{23, 3, 3, 0, 16, 0}
		if err := wire.Nap(); err != nil {
			return err
		}
	case "pad-between-naps":
		frame := make([]byte, 4+65535)
		copy(frame, []byte{0xff, 0xff, 0xff, 0xff})
		for range 5 {
			if _, err := conn.Write(frame); err != nil {
				return err
			}
		}
		if err := wire.Nap(); err != nil {
			return err
		}
		if err := readHello(false); err != nil {
			return err
		}
		return wire.Send(CozyByte, []byte{'b'})
	case "missing-nap":
		// Leave the physical connection open until the client drain times out.
	}
	_, _ = conn.Read(make([]byte, 1))
	return nil
}
