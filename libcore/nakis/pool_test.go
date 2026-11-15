package nakis

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type raceDialer struct {
	started   chan struct{}
	cancelled chan struct{}
	peer      net.Conn
}

func (d *raceDialer) DialContext(ctx context.Context, _ string, dst M.Socksaddr) (net.Conn, error) {
	if dst.Port == 1 {
		close(d.started)
		<-ctx.Done()
		close(d.cancelled)
		return nil, ctx.Err()
	}
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-d.started:
		return d.peer, nil
	}
}

func (*raceDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, net.UnknownNetworkError("udp")
}

func TestColdRaceCancelsLosingEndpoint(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	d := &raceDialer{started: make(chan struct{}), cancelled: make(chan struct{}), peer: a}
	cfg := clientConfig{
		servers:   []M.Socksaddr{M.ParseSocksaddr("127.0.0.1:1"), M.ParseSocksaddr("127.0.0.1:2")},
		raceCount: 2, dialTimeout: time.Second, handshakeTimeout: time.Second,
	}
	p := newPool(context.Background(), cfg, d, nil)
	defer p.close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if !p.begin() {
		t.Fatal("cannot start dial job")
	}
	conn, err := p.race(ctx)
	p.work.Done()
	if err != nil {
		t.Fatal(err)
	}
	if conn.raw != a {
		t.Fatal("wrong winner")
	}
	p.close()
	select {
	case <-d.cancelled:
	default:
		t.Fatal("losing dial was not cancelled and joined")
	}
	if len(p.live) != 0 {
		t.Fatal("retained physical tunnels")
	}
}

type slowCloseTransport struct {
	net.Conn
	closing chan struct{}
	allow   chan struct{}
}

func (c *slowCloseTransport) Close() error {
	close(c.closing)
	<-c.allow
	return c.Conn.Close()
}

func TestPoolNeverChecksOutAbortingTunnel(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	c := &slowCloseTransport{Conn: a, closing: make(chan struct{}), allow: make(chan struct{})}
	var allowOnce sync.Once
	allow := func() { allowOnce.Do(func() { close(c.allow) }) }
	defer allow()
	defer a.Close()
	p := newPool(context.Background(), clientConfig{poolHi: 1, poolMaxAge: time.Minute}, nil, nil)
	phy := &physical{pool: p, raw: c, done: make(chan struct{}), wire: WrapTunnel(c, nil)}
	p.live[phy] = struct{}{}
	abortDone := make(chan struct{})
	go func() { phy.abort(); close(abortDone) }()
	<-c.closing
	putDone := make(chan struct{})
	go func() { p.put(phy); close(putDone) }()
	// A put must not go on to set the idle deadline or start the watcher.
	p.mu.Lock()
	size := len(p.idle)
	p.mu.Unlock()
	if size != 0 || p.take(context.Background()) != nil {
		t.Fatal("issued a lease while Close was still running")
	}
	allow()
	for _, done := range []chan struct{}{putDone, abortDone} {
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Fatal("did not finish aborting")
		}
	}
	if len(p.live) != 0 {
		t.Fatal("aborted tunnel remained live")
	}
}
