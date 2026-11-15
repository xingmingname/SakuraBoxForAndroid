package nakis

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
)

func TestHalfCloseKeepsOppositeDirection(t *testing.T) {
	oracle := os.Getenv("NAKIS_ORACLE")
	if oracle == "" {
		t.Skip("set NAKIS_ORACLE to the UoT/half-close upstream executable")
	}
	l := listenTCP(t)
	addr := l.Addr().String()
	l.Close()
	server := filepath.Join(t.TempDir(), "server.json")
	config, err := json.Marshal(map[string]any{"listen": addr, "uuid": testUUID, "allow_private": true, "tls": false, "log_level": "error"})
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(server, config, 0600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(oracle, "server", "-c", server)
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	until(t, func() bool {
		c, e := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if e != nil {
			return false
		}
		c.Close()
		return true
	})
	id, _ := hex.DecodeString(strings.ReplaceAll(testUUID, "-", ""))
	pool := newPool(context.Background(), clientConfig{
		servers: []M.Socksaddr{M.ParseSocksaddr(addr)}, uuid: id, dialTimeout: time.Second,
		handshakeTimeout: time.Second, poolHi: 2, poolMaxAge: time.Minute, halfCloseTimeout: 2 * time.Second, sendPack: 20480,
	}, loopDialer{}, nil)
	defer pool.close()
	for _, mode := range []string{"application first", "target first"} {
		t.Run(mode, func(t *testing.T) {
			target := listenTCP(t)
			defer target.Close()
			done := make(chan error, 1)
			go func() {
				c, e := target.Accept()
				if e != nil {
					done <- e
					return
				}
				defer c.Close()
				conn := c.(*net.TCPConn)
				_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
				if mode == "target first" {
					if _, e = conn.Write([]byte("reply")); e == nil {
						e = conn.CloseWrite()
					}
					if e != nil {
						done <- e
						return
					}
				}
				incoming, e := io.ReadAll(conn)
				if e == nil && string(incoming) != "request" {
					e = errors.New("incorrect request before EOF")
				}
				if e == nil && mode == "application first" {
					_, e = conn.Write([]byte("reply"))
				}
				done <- e
			}()
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
			defer cancel()
			c, err := pool.dial(ctx, M.ParseSocksaddr(target.Addr().String()))
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			_ = c.SetDeadline(time.Now().Add(3 * time.Second))
			closeWrite := c.(interface{ CloseWrite() error })
			if mode == "target first" {
				reply, e := io.ReadAll(c)
				if e != nil || string(reply) != "reply" {
					t.Fatalf("target reply and EOF: %q %v", reply, e)
				}
			}
			if _, err = c.Write([]byte("request")); err != nil {
				t.Fatal(err)
			}
			if err = closeWrite.CloseWrite(); err != nil {
				t.Fatal(err)
			}
			if err = closeWrite.CloseWrite(); err != nil {
				t.Fatal(err)
			}
			if _, err = c.Write([]byte("invalid")); !errors.Is(err, net.ErrClosed) {
				t.Fatalf("write after local FIN: %v", err)
			}
			if mode == "application first" {
				reply, e := io.ReadAll(c)
				if e != nil || string(reply) != "reply" {
					t.Fatalf("reply after local FIN: %q %v", reply, e)
				}
			}
			if err = <-done; err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestHalfCloseIdleTimeoutReleasesLease(t *testing.T) {
	client, peer := net.Pipe()
	defer peer.Close()
	pool := newPool(context.Background(), clientConfig{
		servers:     []M.Socksaddr{M.ParseSocksaddr("127.0.0.1:1")},
		dialTimeout: time.Second, handshakeTimeout: time.Second, halfCloseTimeout: 500 * time.Millisecond,
		sendPack: 20480,
	}, testPacketDialer{client}, nil)
	defer pool.close()
	pool.warming = true
	peerDone := make(chan error, 1)
	go func() {
		wire := WrapTunnel(peer, nil)
		_, err := wire.Read(make([]byte, 512))
		if err == nil {
			_, err = wire.Read(make([]byte, 512))
		}
		peerDone <- err
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	c, err := pool.dial(ctx, M.ParseSocksaddr("example.invalid:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if err = c.(interface{ CloseWrite() error }).CloseWrite(); err != nil {
		t.Fatal(err)
	}
	if err = <-peerDone; !errors.Is(err, ErrNap) {
		t.Fatalf("missing client NAP: %v", err)
	}
	var one [1]byte
	if _, err = c.Read(one[:]); err == nil {
		t.Fatal("silent half-closed peer did not time out")
	}
	until(t, func() bool { pool.mu.Lock(); defer pool.mu.Unlock(); return len(pool.live) == 0 })
}

type blockedUDPTransport struct {
	net.Conn
	writes  int
	blocked chan struct{}
}

func (c *blockedUDPTransport) SetDeadline(time.Time) error      { return nil }
func (c *blockedUDPTransport) SetWriteDeadline(time.Time) error { return nil }
func (c *blockedUDPTransport) Write(b []byte) (int, error) {
	c.writes++
	if c.writes == 2 {
		close(c.blocked)
	}
	return c.Conn.Write(b)
}

type testPacketDialer struct{ conn net.Conn }

func (d testPacketDialer) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return d.conn, nil
}
func (testPacketDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, net.UnknownNetworkError("udp")
}

func TestUoTCloseInterruptsBlockedWriterAndReleasesLease(t *testing.T) {
	client, peer := net.Pipe()
	defer peer.Close()
	blocked := &blockedUDPTransport{Conn: client, blocked: make(chan struct{})}
	pool := newPool(context.Background(), clientConfig{servers: []M.Socksaddr{M.ParseSocksaddr("127.0.0.1:1")},
		dialTimeout: time.Second, handshakeTimeout: time.Second}, testPacketDialer{blocked}, nil)
	defer pool.close()
	pool.warming = true
	setup := make(chan error, 1)
	go func() {
		wire := WrapTunnel(peer, nil)
		_, e := wire.Read(make([]byte, 512))
		if e == nil {
			e = wire.Send([]byte{RespCozy}, nil)
		}
		setup <- e
		// Deliberately never read the first UDP record.
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	pc, err := pool.listenPacket(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err = <-setup; err != nil {
		t.Fatal(err)
	}
	if _, err = pc.WriteTo([]byte("stall"), M.ParseSocksaddr("127.0.0.1:53")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-blocked.blocked:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	destination := M.ParseSocksaddr("127.0.0.1:53")
	for range 300 {
		if n, err := pc.WriteTo([]byte{1}, destination); n != 1 || err != nil {
			t.Fatalf("small burst did not fit the official queue: %d, %v", n, err)
		}
	}
	payload := make([]byte, 60<<10)
	for range 18 {
		if n, err := pc.WriteTo(payload, destination); n != len(payload) || err != nil {
			t.Fatalf("queue overflow closed the association: %d, %v", n, err)
		}
	}
	udp := pc.(*udpPacketConn)
	udp.mu.Lock()
	length, size := len(udp.queue), udp.bytes
	udp.mu.Unlock()
	if length != 317 || size != 300*10+17*(len(payload)+9) || size > udpQueueLimit {
		t.Fatalf("overflow should drop only new records: %d queued, %d bytes", length, size)
	}
	if err = pc.Close(); err != nil {
		t.Fatal(err)
	}
	until(t, func() bool { pool.mu.Lock(); defer pool.mu.Unlock(); return len(pool.live) == 0 })
}

func TestUDPTimeoutAndDomainUseSameAssociation(t *testing.T) {
	client, peer := net.Pipe()
	defer peer.Close()
	pool := newPool(context.Background(), clientConfig{servers: []M.Socksaddr{M.ParseSocksaddr("127.0.0.1:1")},
		dialTimeout: time.Second, handshakeTimeout: time.Second}, testPacketDialer{client}, nil)
	defer pool.close()
	pool.warming = true
	domain := M.ParseSocksaddr("example.test:53")
	record := append([]byte{3, 12}, []byte("example.test")...)
	record = append(record, 0, 53, 0, 1, 'x')
	peerErr := make(chan error, 1)
	go func() {
		w := WrapTunnel(peer, nil)
		if _, err := w.Read(make([]byte, 512)); err != nil {
			peerErr <- err
			return
		}
		if err := w.Send([]byte{RespCozy}, nil); err != nil {
			peerErr <- err
			return
		}
		incoming := make([]byte, len(record))
		if _, err := io.ReadFull(w, incoming); err != nil {
			peerErr <- err
			return
		}
		if string(incoming) != string(record) {
			peerErr <- errors.New("unexpected domain datagram on wire")
			return
		}
		peerErr <- w.Send(nil, record)
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	pc, err := pool.listenPacket(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	typed := bufio.NewPacketConn(pc)
	b := buf.NewPacket()
	defer b.Release()
	if err := pc.SetReadDeadline(time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := typed.ReadPacket(b); !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatalf("read deadline: %v", err)
	}
	if err := pc.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if err := typed.WritePacket(buf.As([]byte{'x'}), domain); err != nil {
		t.Fatal(err)
	}
	addr, err := typed.ReadPacket(b)
	if err != nil || addr != domain || string(b.Bytes()) != "x" {
		t.Fatalf("domain reply after deadline: %v %v %q", addr, err, b.Bytes())
	}
	if err := <-peerErr; err != nil {
		t.Fatal(err)
	}
}

func TestUDPPartialWireFrameTimeoutClosesLease(t *testing.T) {
	client, peer := net.Pipe()
	defer peer.Close()
	pool := newPool(context.Background(), clientConfig{servers: []M.Socksaddr{M.ParseSocksaddr("127.0.0.1:1")},
		dialTimeout: time.Second, handshakeTimeout: time.Second}, testPacketDialer{client}, nil)
	defer pool.close()
	pool.warming = true
	go func() {
		w := WrapTunnel(peer, nil)
		_, _ = w.Read(make([]byte, 512))
		_ = w.Send([]byte{RespCozy}, nil)
		// One byte of a two-byte frame header.
		_, _ = peer.Write([]byte{0})
	}()
	pc, err := pool.listenPacket(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	if err := pc.SetReadDeadline(time.Now().Add(100 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := pc.ReadFrom(make([]byte, 10)); !errors.Is(err, errPartialFrame) {
		t.Fatalf("partial frame was not distinguished from idle timeout: %v", err)
	}
	if _, err := pc.WriteTo([]byte{'x'}, M.ParseSocksaddr("127.0.0.1:53")); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("partial frame tunnel can still be used: %v", err)
	}
}
