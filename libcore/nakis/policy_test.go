package nakis

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

func TestTLSResumptionChecksPin(t *testing.T) {
	cert := testTLSCertificate(t)
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	pin := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	options := clientConfig{tls: true, sni: "nakis.test", pins: [][sha256.Size]byte{pin}}
	client := clientTLS(context.Background(), options)
	server := &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS13}
	ln := listenTCP(t)
	defer ln.Close()
	peerErr := make(chan error, 1)
	go func() {
		for i := range 3 {
			raw, err := ln.Accept()
			if err != nil {
				peerErr <- err
				return
			}
			_ = raw.SetDeadline(time.Now().Add(2 * time.Second))
			conn := tls.Server(raw, server)
			_, err = conn.Write([]byte{0, 1, 'a'})
			raw.Close()
			if i < 2 && err != nil {
				peerErr <- err
				return
			}
		}
		peerErr <- nil
	}()

	exchange := func(config *tls.Config, wantResume, reject bool) {
		t.Helper()
		seen, resumed := false, false
		verify := config.VerifyConnection
		config.VerifyConnection = func(state tls.ConnectionState) error {
			seen, resumed = true, state.DidResume
			return verify(state)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		raw, err := (&net.Dialer{}).DialContext(ctx, "tcp", ln.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		defer raw.Close()
		if err := raw.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
			t.Fatal(err)
		}
		wire, err := secure(ctx, raw, config)
		if !seen || resumed != wantResume {
			t.Fatalf("verification callback seen=%v resumed=%v, want resumed=%v: %v", seen, resumed, wantResume, err)
		}
		if reject {
			if err == nil || !strings.Contains(err.Error(), "did not match a configured pin") {
				t.Fatalf("expected pin rejection on resumed handshake, got %v", err)
			}
			return
		}
		if err != nil {
			t.Fatal(err)
		}
		// This read processes the server's session ticket before the next dial.
		if b, err := wire.ReadByte(); b != 'a' || err != nil {
			t.Fatalf("TLS reply %q: %v", b, err)
		}
	}

	exchange(client.Clone(), false, false)
	exchange(client.Clone(), true, false)
	options.pins = [][sha256.Size]byte{{}}
	wrong := clientTLS(context.Background(), options)
	// Force a resumption attempt to exercise the callback even with an old
	// ticket. Outbounds normally keep caches separate per security config.
	wrong.ClientSessionCache = client.ClientSessionCache
	exchange(wrong, true, true)
	if err := <-peerErr; err != nil {
		t.Fatal(err)
	}
}

func TestUpdatedPersonaAndLifespan(t *testing.T) {
	for _, port := range []uint16{443, 465, 563, 636, 853, 993, 995, 5223, 8443} {
		if !tlsLike(port) {
			t.Fatalf("port %d was not recognized", port)
		}
	}
	if tlsLike(80) || tlsLike(8080) {
		t.Fatal("ordinary target port selected TLS-like plan")
	}
	for range 512 {
		for _, port := range []uint16{80, 443} {
			inner := tlsLike(port)
			plan := personaPlan(true, inner, 310)
			lo, hi := 3, 5
			if inner {
				lo, hi = 5, 7
			}
			if len(plan) < lo || len(plan) > hi || plan[0].Min < 310 || plan[0].Max > ShapeCap {
				t.Fatalf("unexpected plan for port %d: %+v", port, plan)
			}
		}
		for _, maxUses := range []int{0, 1, 7, 8, 100} {
			life := newLife(maxUses)
			if life.bytes < minLifeBytes || life.bytes > maxLifeBytes || life.uses < (maxUses+1)/2 || life.uses > maxUses {
				t.Fatalf("unexpected life for %d: %+v", maxUses, life)
			}
			if life.spent(0, life.bytes-1) || !life.spent(0, life.bytes) ||
				life.spent(life.uses, 0) != (maxUses != 0) {
				t.Fatalf("incorrect retirement boundary: %+v", life)
			}
		}
	}
	wire := &Tunnel{}
	wire.noteMoved(maxLifeBytes - 1)
	wire.noteMoved(2)
	wire.noteMoved(1)
	if wire.Moved() != maxLifeBytes {
		t.Fatalf("wire count did not saturate: %d", wire.Moved())
	}
	p := &tunnelPool{}
	if got := p.drainWindow(); got != 500*time.Millisecond {
		t.Fatalf("drain floor = %v", got)
	}
	p.observeHandshake(700 * time.Millisecond)
	p.observeHandshake(1500 * time.Millisecond)
	if got := p.drainWindow(); got != 1600*time.Millisecond {
		t.Fatalf("drain EWMA = %v", got)
	}
	p.handshakeAvg.Store(int64(time.Minute))
	if got := p.drainWindow(); got != 5*time.Second {
		t.Fatalf("drain cap = %v", got)
	}
}

func TestPoolConcurrentCloseDuringDrain(t *testing.T) {
	ln := listenTCP(t)
	defer ln.Close()
	const count = 16
	peersDone := make(chan struct{}, count)
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_, _ = io.Copy(io.Discard, conn)
				peersDone <- struct{}{}
			}()
		}
	}()
	cfg := clientConfig{
		servers:     []M.Socksaddr{M.ParseSocksaddr(ln.Addr().String())},
		dialTimeout: time.Second, handshakeTimeout: time.Second, poolMaxAge: time.Minute,
		sendPack: 1024,
	}
	p := newPool(context.Background(), cfg, loopDialer{}, nil)
	defer p.close()
	p.warming = true
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	dials := make(chan error, count)
	for range count {
		go func() {
			c, err := p.dial(ctx, M.ParseSocksaddr("example.com:80"))
			if err == nil {
				err = c.Close()
			}
			dials <- err
		}()
	}
	for range count {
		if err := <-dials; err != nil {
			t.Fatal(err)
		}
	}
	done := make(chan struct{})
	go func() { p.close(); close(done) }()
	select {
	case <-done:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	for range count {
		select {
		case <-peersDone:
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
	}
	if len(p.live) != 0 {
		t.Fatal("live tunnels remained after close")
	}
	if _, err := p.dial(ctx, M.ParseSocksaddr("example.com:80")); !errors.Is(err, errClosed) {
		t.Fatalf("dial after close: %v", err)
	}
}

type noReadDeadlineConn struct{ net.Conn }

func (c noReadDeadlineConn) SetReadDeadline(time.Time) error { return nil }

func TestPoolDiscardsTransportThatCannotConfirmIdle(t *testing.T) {
	client, peer := net.Pipe()
	defer peer.Close()
	p := newPool(context.Background(), clientConfig{poolHi: 1, poolMaxAge: time.Minute}, nil, nil)
	defer p.close()
	raw := noReadDeadlineConn{client}
	physical := &physical{pool: p, raw: raw, wire: WrapTunnel(raw, nil)}
	p.live[physical] = struct{}{}
	p.put(physical)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if got := p.take(ctx); got != nil || ctx.Err() != nil {
		t.Fatal("checkout did not discard the unconfirmed transport within its own timeout")
	}
	if len(p.live) != 0 {
		t.Fatal("discarded transport remained live")
	}
}

type permanentDeadline struct{}

func (permanentDeadline) Error() string   { return "permanent read deadline" }
func (permanentDeadline) Unwrap() error   { return os.ErrDeadlineExceeded }
func (permanentDeadline) Timeout() bool   { return true }
func (permanentDeadline) Temporary() bool { return false }

type permanentlyTimedOutConn struct{ net.Conn }

func (permanentlyTimedOutConn) Read([]byte) (int, error) { return 0, permanentDeadline{} }

func TestIdleProbeRejectsPermanentReadDeadline(t *testing.T) {
	wire := WrapTunnel(permanentlyTimedOutConn{}, nil)
	if wire.idleClean(make(chan struct{})) {
		t.Fatal("permanent deadline was mistaken for a clean connection")
	}
}

func TestUpstreamNormalization(t *testing.T) {
	opts := OutboundOptions{
		ServerOptions: option.ServerOptions{Server: " PRIMARY.TEST. ", ServerPort: 443},
		ServerAlternates: []string{"primary.test:0443", "", "[2001:0DB8::0001]:8443", "[2001:db8::1]:8443",
			"[::ffff:c000:201]:443", "[FE80::1%Eth0]:8443"},
		UUID: testUUID, SNI: " FRONT.TEST. ",
	}
	cfg, err := resolveOptions(opts)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"primary.test:443", "[2001:db8::1]:8443", "[::ffff:192.0.2.1]:443", "[fe80::1%Eth0]:8443"}
	if len(cfg.servers) != len(want) || cfg.sni != "front.test" {
		t.Fatalf("servers = %v, SNI = %q", cfg.servers, cfg.sni)
	}
	for i, server := range cfg.servers {
		if server.String() != want[i] {
			t.Fatalf("server[%d] = %s; want %s", i, server, want[i])
		}
	}
	for _, invalid := range []string{"other..test:443", "other.test:0", "other.test:+443", "[example%eth0]:443"} {
		bad := opts
		bad.ServerAlternates = []string{invalid}
		if err := ValidateOptions(bad); err == nil {
			t.Fatalf("accepted alternate %q", invalid)
		}
	}
	opts.SNI = "fe80::1%Eth0"
	if err := ValidateOptions(opts); err == nil {
		t.Fatal("accepted a scoped SNI")
	}
}

func TestShapingTimerSurvivesShortWrites(t *testing.T) {
	var data []byte
	g := NewGate(GateOptions{Pack: 1024, Plan: []Range{{Min: 8, Max: 8}}, Fill: time.Hour},
		func(wire []byte, _ int) error {
			data = append(data, wire...)
			return nil
		})
	t.Cleanup(g.Close)
	g.Feed([]byte("ab"))
	timer, epoch := g.fillTimer, g.fillEpoch
	if timer == nil {
		t.Fatal("the initial short write did not arm the shaping timer")
	}
	g.Feed([]byte("cd"))
	if g.fillTimer != timer || g.fillEpoch != epoch {
		t.Fatal("the second short write restarted the initial shaping timer")
	}
	g.Feed([]byte("efgh"))
	if !bytes.Equal(data, []byte("abcdefgh")) || g.fillTimer != nil {
		t.Fatalf("frame=%q; pending timer=%v", data, g.fillTimer != nil)
	}
}
