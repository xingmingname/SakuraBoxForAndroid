package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/protocol/group"
)

func profileTestBox(t *testing.T) (*BoxInstance, *ProfileTrafficStats) {
	t.Helper()
	b, err := newSingBoxInstance(`{"log":{"disabled":true},"outbounds":[{"type":"selector","tag":"proxy","outbounds":["a","b"]},{"type":"direct","tag":"a"},{"type":"direct","tag":"b"},{"type":"direct","tag":"bypass"}]}`, nil, new(regressionPlatform))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	s, err := b.NewProfileTrafficStats("a\nb\nbypass", "proxy")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = b.NewProfileTrafficStats("a", "proxy"); err == nil {
		t.Fatal("duplicate tracker allowed")
	}
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	return b, s
}
func profileValues(t *testing.T, s *ProfileTrafficStats) map[string]profileTrafficValue {
	t.Helper()
	var v map[string]profileTrafficValue
	if err := json.Unmarshal([]byte(s.Snapshot()), &v); err != nil {
		t.Fatal(err)
	}
	return v
}
func TestProfileTrafficSelectorLifetimeAndClose(t *testing.T) {
	b, s := profileTestBox(t)
	out, _ := b.Outbound().Outbound("proxy")
	selector := out.(*group.Selector)
	old := s.RoutedFlow(context.Background(), adapter.InboundContext{}, nil, out)
	old.CountReverse(100)
	if !selector.SelectOutbound("b") {
		t.Fatal("switch failed")
	}
	fresh := s.RoutedFlow(context.Background(), adapter.InboundContext{}, nil, out)
	old.CountReverse(50)
	fresh.CountReverse(200)
	if !selector.SelectOutbound("a") {
		t.Fatal("switch back failed")
	}
	third := s.RoutedFlow(context.Background(), adapter.InboundContext{}, nil, out)
	third.CountReverse(75)
	fresh.CountReverse(50)
	v := profileValues(t, s)
	if v["a"].Rx != 225 || v["b"].Rx != 250 {
		t.Fatalf("wrong lifetime owners: %+v", v)
	}
	if again := profileValues(t, s); again["a"] != v["a"] {
		t.Fatal("snapshot consumed bytes")
	}
	// Close must not destroy final unsampled counters.
	old.CountReverse(2048)
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	if profileValues(t, s)["a"].Rx != 225+2048 {
		t.Fatal("tail lost")
	}
	if _, err := b.NewProfileTrafficStats("a", ""); err == nil {
		t.Fatal("tracker attached after close")
	}
}
func TestProfileTrafficLargeValuesConcurrentAndResetIndependent(t *testing.T) {
	b, s := profileTestBox(t)
	out, _ := b.Outbound().Outbound("a")
	f := s.RoutedFlow(context.Background(), adapter.InboundContext{}, nil, out)
	b.SetV2rayStats("a")
	for _, n := range []int64{30_000_000_000, 100_000_000_000, 5 << 40} {
		// No large allocation or network use; test 32-bit-safe chunks.
		left := n
		for left > 0 {
			chunk := min(left, 1<<30)
			f.CountReverse(int(chunk))
			left -= chunk
		}
	}
	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			for range 10000 {
				f.CountForward(100)
				_ = s.Snapshot()
			}
		})
	}
	wg.Wait()
	v := profileValues(t, s)["a"]
	if v.Rx != 30_000_000_000+100_000_000_000+5<<40 || v.Tx != 8_000_000 {
		t.Fatalf("bad totals %+v", v)
	}
	_ = b.QueryStats("a", "downlink")
	if profileValues(t, s)["a"] != v {
		t.Fatal("legacy QueryStats interfered")
	}
	f.CountForward(-1)
	f.CountReverse(-1)
	if profileValues(t, s)["a"] != v {
		t.Fatal("negative flow length changed history")
	}
}
func TestProfileTrafficTCPAndUDPWrappers(t *testing.T) {
	b, s := profileTestBox(t)
	out, _ := b.Outbound().Outbound("a")
	left, right := net.Pipe()
	defer right.Close()
	wrapped := s.RoutedConnection(context.Background(), left, adapter.InboundContext{}, nil, out)
	done := make(chan error, 1)
	go func() { _, err := right.Write([]byte("upload")); done <- err }()
	streamPayload := make([]byte, 6)
	if _, err := io.ReadFull(wrapped, streamPayload); err != nil {
		t.Fatal(err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	wrapped.Close()
	// A real loopback packet conn exercises sing's counter packet wrapper.
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	peer, err := net.Dial("udp", udp.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()

	udp.SetDeadline(time.Now().Add(3 * time.Second))
	peer.SetDeadline(time.Now().Add(3 * time.Second))
	packets := s.RoutedPacketConnection(context.Background(), bufio.NewPacketConn(udp), adapter.InboundContext{}, nil, out)
	if _, err = peer.Write([]byte("udp")); err != nil {
		t.Fatal(err)
	}
	packet := buf.NewSize(2048)
	destination, err := packets.ReadPacket(packet)
	if err != nil {
		t.Fatal(err)
	}
	if string(packet.Bytes()) != "udp" {
		t.Fatal("UDP payload changed")
	}
	packet.Release()
	if err = packets.WritePacket(buf.As([]byte("reply")), destination); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 5)
	if _, err = io.ReadFull(peer, reply); err != nil {
		t.Fatal(err)
	}
	v := profileValues(t, s)["a"]
	if v.Tx != 9 || v.Rx != 5 {
		t.Fatalf("TCP/UDP direction mismatch: %+v", v)
	}

	if s.RoutedFlow(context.Background(), adapter.InboundContext{}, nil, nil) != nil {
		t.Fatal("nil outbound was attributed")
	}
}

func TestProfileTrafficActualRouterAndIndependentMonitor(t *testing.T) {
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		c, e := echo.Accept()
		if e == nil {
			defer c.Close()
			io.Copy(c, c)
		}
	}()
	port, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	proxyPort := port.Addr().(*net.TCPAddr).Port
	port.Close()
	config := fmt.Sprintf(`{"log":{"disabled":true},"inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","listen_port":%d}],"outbounds":[{"type":"direct","tag":"a"}],"route":{"final":"a"}}`, proxyPort)
	b, err := newSingBoxInstance(config, nil, new(regressionPlatform))
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	s, err := b.NewProfileTrafficStats("a", "")
	if err != nil {
		t.Fatal(err)
	}
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", proxyPort), 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	c.Write([]byte{5, 1, 0})
	response := make([]byte, 2)
	if _, err = io.ReadFull(c, response); err != nil {
		t.Fatal(err)
	}
	ep := echo.Addr().(*net.TCPAddr).Port
	c.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, byte(ep >> 8), byte(ep)})
	header := make([]byte, 10)
	if _, err = io.ReadFull(c, header); err != nil {
		t.Fatal(err)
	}
	payload := []byte("profile-router-loopback")
	if _, err = c.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err = io.ReadFull(c, got); err != nil {
		t.Fatal(err)
	}
	c.Close()
	deadline := time.Now().Add(time.Second)
	for {
		v := profileValues(t, s)["a"]
		if v.Tx == int64(len(payload)) && v.Rx == int64(len(payload)) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("router did not count exact payload: %+v", v)
		}
		time.Sleep(time.Millisecond)
	}
	before := s.Snapshot()
	_ = b.trafficTracker.Connections()
	if s.Snapshot() != before {
		t.Fatal("connection monitor consumed profile statistics")
	}
}

func TestProfileTrafficActiveURLTestScopeAndSelector(t *testing.T) {
	b, s := profileTestBox(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) }))
	defer server.Close()
	if _, err := UrlTest(b, server.URL, 3000); err != nil {
		t.Fatal(err)
	}
	first := profileValues(t, s)
	if first["a"].Rx <= 0 || first["a"].Tx <= 0 {
		t.Fatal("active URL Test bytes no longer counted")
	}
	out, _ := b.Outbound().Outbound("proxy")
	if !out.(*group.Selector).SelectOutbound("b") {
		t.Fatal("switch failed")
	}
	if _, err := UrlTest(b, server.URL, 3000); err != nil {
		t.Fatal(err)
	}
	second := profileValues(t, s)
	if second["a"] != first["a"] || second["b"].Rx <= 0 || second["b"].Tx <= 0 {
		t.Fatalf("URL Test ownership changed: %+v", second)
	}
}
