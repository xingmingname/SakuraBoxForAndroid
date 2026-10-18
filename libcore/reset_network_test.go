package libcore

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/nekoutils"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

// These exercise the production constructor and reset entry point against the
// locked core and real loopback sockets. Android protection is simulated.
func sr01Box(t *testing.T, start bool) *BoxInstance {
	t.Helper()
	b, err := newSingBoxInstance(`{"log":{"disabled":true},"route":{"auto_detect_interface":true,"final":"direct"},"outbounds":[{"type":"direct","tag":"direct"}]}`, nil, new(regressionPlatform))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	if start {
		if err = b.Start(); err != nil {
			t.Fatal(err)
		}
	}
	return b
}

func sr01Main(t *testing.T, b *BoxInstance) {
	t.Helper()
	previous := mainInstance.Swap(b)
	t.Cleanup(func() { mainInstance.CompareAndSwap(b, previous) })
}

func sr01TCPPeer(t *testing.T) net.Listener {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			peer, err := listener.Accept()
			if err != nil {
				return
			}
			go func() { defer peer.Close(); io.Copy(peer, peer) }()
		}
	}()
	return listener
}

func sr01Echo(conn net.Conn) error {
	conn.SetDeadline(time.Now().Add(time.Second))
	if _, err := conn.Write([]byte("reset-check")); err != nil {
		return err
	}
	_, err := io.ReadFull(conn, make([]byte, len("reset-check")))
	return err
}

func TestSR01ProductionTrackerRegistration(t *testing.T) {
	b := sr01Box(t, false)
	if b.trafficTracker == nil {
		t.Fatal("production constructor lost the core's registered traffic manager")
	}
	if b.trafficTracker != service.PtrFromContext[trafficcontrol.Manager](b.ctx) {
		t.Fatal("constructor returned a different tracker than the actual core registry")
	}
}

type sr01Observer struct {
	adapter.Outbound
	callback func(context.Context)
}

func (*sr01Observer) Tag() string                            { return "sr01-observer" }
func (o *sr01Observer) InterfaceUpdated(ctx context.Context) { o.callback(ctx) }

// Observe the real core manager's listener dispatch without replacing it or
// changing the production constructor/registries used to construct the Box.
func sr01Observe(t *testing.T, b *BoxInstance, callback func(context.Context)) {
	t.Helper()
	registry := service.FromContext[adapter.OutboundRegistry](b.ctx).(*outbound.Registry)
	base := b.Outbound().Default()
	outbound.Register[struct{}](registry, "sr01-observer", func(context.Context, adapter.Router, log.ContextLogger, string, struct{}) (adapter.Outbound, error) {
		return &sr01Observer{base, callback}, nil
	})
	if err := b.Outbound().Create(b.ctx, b.Router(), log.NewNOPFactory().NewLogger("test"), "sr01-observer", "sr01-observer", &struct{}{}); err != nil {
		t.Fatal(err)
	}
}

func TestSR01LifecycleAndContextBoundaries(t *testing.T) {
	b := sr01Box(t, false)
	sr01Main(t, b)
	var calls atomic.Int32
	sr01Observe(t, b, func(ctx context.Context) {
		if ctx != b.ctx || ctx.Err() != nil || service.FromContext[adapter.NetworkManager](ctx) != b.Network() {
			t.Error("notification did not receive this instance's live core context")
		}
		calls.Add(1)
	})
	ResetAllConnections(true) // Published but not started: no partial-core reset.
	if calls.Load() != 0 {
		t.Fatal("reset before Start")
	}
	if err := b.Start(); err != nil {
		t.Fatal(err)
	}
	ResetAllConnections(false) // Preserve the existing non-system behavior.
	if calls.Load() != 0 {
		t.Fatal("non-system reset unexpectedly changed behavior")
	}
	ResetAllConnections(true)
	ResetAllConnections(true)
	if calls.Load() != 2 {
		t.Fatal("full reset did not notify exactly once per call")
	}
	b.cancel()
	ResetAllConnections(true)
	if calls.Load() != 2 {
		t.Fatal("reset used a canceled context")
	}
	b.Close()
	b.resetNetwork() // Includes a queued callback holding a formerly-main pointer.
	if calls.Load() != 2 {
		t.Fatal("reset after Close")
	}
	if b.ctx.Err() == nil {
		t.Fatal("Close did not cancel instance context")
	}
}

func TestSR01FailedStartCannotResetPartialCore(t *testing.T) {
	occupied, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	config := fmt.Sprintf(`{"log":{"disabled":true},"inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":%d}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}`, occupied.Addr().(*net.TCPAddr).Port)
	b, err := newSingBoxInstance(config, nil, new(regressionPlatform))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	sr01Main(t, b)
	var calls atomic.Int32
	sr01Observe(t, b, func(context.Context) { calls.Add(1) })
	if err = b.Start(); err == nil {
		t.Fatal("occupied inbound unexpectedly started")
	}
	ResetAllConnections(true)
	if b.started || calls.Load() != 0 {
		t.Fatal("failed Start exposed partial core to reset")
	}
}

func TestSR01RoutedTrackerConnectionsAreClosed(t *testing.T) {
	port := loopbackPort(t, "tcp")
	config := fmt.Sprintf(`{"log":{"disabled":true},"inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":%d}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}`, port)
	b, err := newSingBoxInstance(config, nil, new(regressionPlatform))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	sr01Main(t, b)
	peer := sr01TCPPeer(t)
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(2 * time.Second))
	fmt.Fprintf(conn, "CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", peer.Addr(), peer.Addr())
	response, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil || response.StatusCode != 200 {
		t.Fatal("local CONNECT failed", err)
	}
	if err = sr01Echo(conn); err != nil {
		t.Fatal(err)
	}
	if b.trafficTracker == nil || b.trafficTracker.ConnectionsLen() == 0 {
		t.Fatal("routed session was not tracked")
	}
	ResetAllConnections(true)
	if err = sr01Echo(conn); err == nil {
		t.Fatal("routed session survived reset")
	}
	deadline := time.Now().Add(2 * time.Second)
	for b.trafficTracker.ConnectionsLen() != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if b.trafficTracker.ConnectionsLen() != 0 {
		t.Fatal("closed routed tracker remained active")
	}
}

func TestSR01ResetSerializesWithCloseAndOtherResets(t *testing.T) {
	b := sr01Box(t, true)
	sr01Main(t, b)
	entered, release := make(chan struct{}), make(chan struct{})
	var first sync.Once
	sr01Observe(t, b, func(context.Context) { first.Do(func() { close(entered); <-release }) })
	resetDone := make(chan struct{})
	go func() { ResetAllConnections(true); close(resetDone) }()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("reset never reached core listener")
	}
	closeDone := make(chan struct{})
	go func() { b.Close(); close(closeDone) }()
	select {
	case <-closeDone:
		close(release)
		t.Fatal("Close raced the in-flight core reset")
	case <-time.After(30 * time.Millisecond):
	}
	close(release)
	for _, done := range []chan struct{}{resetDone, closeDone} {
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Fatal("reset/Close deadlocked")
		}
	}
	b = sr01Box(t, true)
	sr01Main(t, b)
	var calls atomic.Int32
	sr01Observe(t, b, func(context.Context) { calls.Add(1) })
	var workers sync.WaitGroup
	for i := 0; i < 24; i++ {
		workers.Add(1)
		go func() { defer workers.Done(); ResetAllConnections(true) }()
	}
	completed := make(chan struct{})
	go func() { workers.Wait(); close(completed) }()
	select {
	case <-completed:
	case <-time.After(5 * time.Second):
		t.Fatal("concurrent resets stalled")
	}
	if calls.Load() != 24 {
		t.Fatal("concurrent reset notifications lost or duplicated")
	}
}

func TestSR01MainResetDoesNotResetIndependentB(t *testing.T) {
	a := sr01Box(t, true)
	sr01Main(t, a)
	target, hits := pinnedTarget(t)
	peer, peerHits := pinnedProxy(t, target.Listener.Addr().String())
	b := pinnedClient(t, peer, "ip", false, newPinnedControl(), new(pinnedResolver))
	var notifications atomic.Int32
	sr01Observe(t, b, func(context.Context) { notifications.Add(1) })
	for i := 0; i < 3; i++ {
		ResetAllConnections(true)
		if _, err := UrlTest(b, target.URL, 1500); err != nil {
			t.Fatal("main reset disrupted independent B", err)
		}
	}
	if notifications.Load() != 0 || hits.Load() != 6 || peerHits.Load() == 0 {
		t.Fatal("main reset touched B or B requests bypassed its peer")
	}
}

func TestSR01SelectorCallbackCanResetWithoutDeadlock(t *testing.T) {
	b, err := newSingBoxInstance(`{"log":{"disabled":true},"outbounds":[{"type":"selector","tag":"proxy","outbounds":["first","second"]},{"type":"direct","tag":"first"},{"type":"direct","tag":"second"}],"route":{"final":"proxy"}}`, nil, new(regressionPlatform))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	sr01Main(t, b)
	previous := nekoutils.Selector_OnProxySelected
	t.Cleanup(func() { nekoutils.Selector_OnProxySelected = previous })
	var notifications atomic.Int32
	sr01Observe(t, b, func(context.Context) { notifications.Add(1) })
	nekoutils.Selector_OnProxySelected = func(selector, tag string) {
		if selector != "proxy" || tag != "second" {
			t.Error("unexpected selector callback")
		}
		ResetAllConnections(true) // Mirrors the existing Android NativeInterface callback.
	}
	done := make(chan bool, 1)
	go func() { done <- b.SelectOutbound("second") }()
	select {
	case selected := <-done:
		if !selected || notifications.Load() != 1 {
			t.Fatal("selection/reset callback was not completed")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("selector callback deadlocked with reset")
	}
}

func TestSR01ResetClosesTCPUDPAndAllowsReconnect(t *testing.T) {
	b := sr01Box(t, true)
	sr01Main(t, b)
	tcpPeer := sr01TCPPeer(t)
	udpPeer, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udpPeer.Close() })
	go func() {
		buffer := make([]byte, 64)
		for {
			n, address, err := udpPeer.ReadFrom(buffer)
			if err != nil {
				return
			}
			udpPeer.WriteTo(buffer[:n], address)
		}
	}()
	dialTCP := func() net.Conn {
		conn, err := b.Outbound().Default().DialContext(context.Background(), "tcp", M.ParseSocksaddr(tcpPeer.Addr().String()))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { conn.Close() })
		return conn
	}
	dialUDP := func() net.PacketConn {
		conn, err := b.Outbound().Default().ListenPacket(context.Background(), M.ParseSocksaddr(udpPeer.LocalAddr().String()))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { conn.Close() })
		return conn
	}
	echoUDP := func(conn net.PacketConn) error {
		conn.SetDeadline(time.Now().Add(time.Second))
		if _, err := conn.WriteTo([]byte("reset-check"), udpPeer.LocalAddr()); err != nil {
			return err
		}
		_, _, err := conn.ReadFrom(make([]byte, 64))
		return err
	}
	tcp, udp := dialTCP(), dialUDP()
	if err = sr01Echo(tcp); err != nil {
		t.Fatal(err)
	}
	if err = echoUDP(udp); err != nil {
		t.Fatal(err)
	}
	ResetAllConnections(true)
	if err = sr01Echo(tcp); err == nil {
		t.Error("reset left the existing TCP socket usable")
	}
	if err = echoUDP(udp); err == nil {
		t.Error("reset left the existing UDP socket usable")
	}
	if err = sr01Echo(dialTCP()); err != nil {
		t.Fatal("TCP reconnect failed after reset:", err)
	}
	if err = echoUDP(dialUDP()); err != nil {
		t.Fatal("UDP reconnect failed after reset:", err)
	}
}
