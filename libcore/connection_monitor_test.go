package libcore

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	M "github.com/sagernet/sing/common/metadata"
)

func monitorTestConn(t *testing.T, b *BoxInstance, network, domain string) net.Conn {
	t.Helper()
	left, right := net.Pipe()
	t.Cleanup(func() { right.Close() })
	return b.trafficTracker.RoutedConnection(context.Background(), left, adapter.InboundContext{
		Network: network, Source: M.ParseSocksaddr("[::1]:2000"),
		Destination:       M.ParseSocksaddr("[2001:db8::2]:443"),
		OriginDestination: M.ParseSocksaddr(domain + ":443"),
	}, nil, b.Outbound().Default())
}

func monitorFrames(t *testing.T, m *ConnectionMonitor) (<-chan monitorFrame, <-chan error) {
	t.Helper()
	reader, writer := io.Pipe()
	frames := make(chan monitorFrame, 4096)
	done := make(chan error, 1)
	go func() {
		defer close(frames)
		decoder := json.NewDecoder(reader)
		for {
			var f monitorFrame
			if decoder.Decode(&f) != nil {
				return
			}
			select {
			case frames <- f:
			case <-m.ctx.Done():
				return
			}
		}
	}()
	go func() {
		done <- m.stream(writer)
		writer.Close()
	}()
	t.Cleanup(func() { m.Close(); reader.Close(); writer.Close() })
	return frames, done
}

func monitorWait(t *testing.T, frames <-chan monitorFrame, matches func(monitorFrame) bool) monitorFrame {
	t.Helper()
	timer := time.NewTimer(4 * time.Second)
	defer timer.Stop()
	for {
		select {
		case frame, ok := <-frames:
			if !ok {
				t.Fatal("monitor ended before expected frame")
			}
			if matches(frame) {
				return frame
			}
		case <-timer.C:
			t.Fatal("monitor frame timed out")
		}
	}
}

func TestConnectionMonitorCountTrafficAndClose(t *testing.T) {
	b := sr01Box(t, true)
	monitor, err := b.NewConnectionMonitor("")
	if err != nil {
		t.Fatal(err)
	}
	frames, done := monitorFrames(t, monitor)
	monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "status" && f.Count == 0 })
	conn := monitorTestConn(t, b, "tcp", "original.example")
	defer conn.Close()
	frame := monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "connection" })
	id := frame.Connection.ID
	if frame.Connection.Domain != "original.example" || frame.Connection.Destination != "2001:db8::2" ||
		frame.Connection.Source != "::1" || frame.Connection.Port != 443 || frame.Connection.Network != "tcp" {
		t.Fatalf("incorrect recorded metadata: %+v", frame.Connection)
	}
	tracker := b.trafficTracker.Connection(uuid.FromStringOrNil(id))
	tracker.Metadata().Upload.Store(125)
	tracker.Metadata().Download.Store(456)
	monitorWait(t, frames, func(f monitorFrame) bool {
		if f.Type != "traffic" {
			return false
		}
		for _, item := range f.Traffic {
			if item.ID == id && item.Upload == 125 && item.Download == 456 {
				return true
			}
		}
		return false
	})
	if err = conn.Close(); err != nil {
		t.Fatal(err)
	}
	f := monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "closed" && f.ID == id })
	if f.ClosedAt == 0 || f.Connection == nil || f.Connection.Upload != 125 || f.Connection.Download != 456 {
		t.Fatalf("final traffic missing: %+v", f)
	}
	monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "status" && f.Count == b.trafficTracker.ConnectionsLen() })
	var calls sync.WaitGroup
	for i := 0; i < 16; i++ {
		calls.Add(1)
		go func() { defer calls.Done(); monitor.Close() }()
	}
	calls.Wait()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Close did not stop stream")
	}
}

func TestConnectionMonitorReconcilesDroppedEvents(t *testing.T) {
	b := sr01Box(t, true)
	conn := monitorTestConn(t, b, "udp", "udp.example")
	m, err := b.NewConnectionMonitor("")
	if err != nil {
		t.Fatal(err)
	}
	// Deliberately withhold events to exercise the bounded queue reconciliation.
	m.manager.UnSubscribeEvents(m.events)
	m.events = make(chan trafficcontrol.ConnectionEvent)
	m.done = make(chan struct{})
	frames, _ := monitorFrames(t, m)
	f := monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "connection" })
	if f.Connection.Network != "udp" {
		t.Fatal("UDP metadata lost")
	}
	id := f.Connection.ID
	conn.Close()
	monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "closed" && f.ID == id })
	monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "status" && f.Count == 0 })
}

func TestConnectionMonitorFilterAndRecentClose(t *testing.T) {
	b := sr01Box(t, true)
	a := monitorTestConn(t, b, "tcp", "one.example")
	c := monitorTestConn(t, b, "udp", "two.example")
	defer a.Close()
	defer c.Close()
	connections := b.trafficTracker.Connections()
	id := connections[0].ID.String()
	m, err := b.NewConnectionMonitor(id)
	if err != nil {
		t.Fatal(err)
	}
	frames, _ := monitorFrames(t, m)
	f := monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "connection" })
	if f.Connection.ID != id {
		t.Fatal("detail leaked another connection")
	}
	monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "status" && f.Count == 2 })
	b.trafficTracker.Connection(connections[0].ID).Close()
	monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "closed" && f.ID == id })
	m.Close()
	reopen, err := b.NewConnectionMonitor(id)
	if err != nil {
		t.Fatal(err)
	}
	reopened, _ := monitorFrames(t, reopen)
	f = monitorWait(t, reopened, func(f monitorFrame) bool { return f.Type == "closed" })
	if f.Connection == nil || f.Connection.ID != id || f.ClosedAt == 0 {
		t.Fatal("closed detail incorrectly became active")
	}
}

func TestConnectionMonitorMetadataDoesNotInventIPOrDomain(t *testing.T) {
	now := time.Now()
	metadata := &trafficcontrol.TrackerMetadata{
		ID: uuid.FromStringOrNil("a532dc76-2a7d-413f-86fc-5335b76d9084"), CreatedAt: now,
		Metadata: adapter.InboundContext{
			Network: "tcp", Destination: M.ParseSocksaddr("192.0.2.7:80"),
			ProcessInfo: &adapter.ConnectionOwner{AndroidPackageNames: []string{"test.package"}},
		},
		Upload: new(atomic.Int64), Download: new(atomic.Int64),
	}
	metadata.Metadata.RouteRule = "recorded rule"
	metadata.Chain = []string{"direct", "proxy"}
	listValue := monitorMetadata(metadata, time.Time{}, false)
	if listValue.Rule != "" || len(listValue.Chain) != 0 || len(listValue.Packages) != 0 {
		t.Fatal("list included unused detail-only metadata")
	}
	value := monitorMetadata(metadata, time.Time{}, true)
	if value.Domain != "" || value.Destination != "192.0.2.7" || value.Packages[0] != "test.package" {
		t.Fatalf("incorrect fallback: %+v", value)
	}
	metadata.Metadata.Destination = M.ParseSocksaddr("domain.example:443")
	value = monitorMetadata(metadata, time.Time{}, true)
	if value.Domain != "domain.example" || value.Destination != "" {
		t.Fatal("resolved IP was invented")
	}
	metadata.Metadata.Domain = "sniff.example"
	metadata.Metadata.OriginDestination = M.ParseSocksaddr("original.example:443")
	if monitorMetadata(metadata, now.Add(time.Second), true).Domain != "original.example" {
		t.Fatal("original domain lost priority")
	}
	if monitorMetadata(metadata, now.Add(time.Second), true).Duration != 1000 {
		t.Fatal("closed duration did not freeze")
	}
}

func TestConnectionMonitorLifecycleAndMemory(t *testing.T) {
	b := sr01Box(t, false)
	if _, err := b.NewConnectionMonitor(""); err == nil {
		t.Fatal("unstarted core exposed monitor")
	}
	if err := b.Start(); err != nil {
		t.Fatal(err)
	}
	if _, err := b.NewConnectionMonitor("not-a-uuid"); err == nil {
		t.Fatal("invalid detail ID accepted")
	}
	m, err := b.NewConnectionMonitor("")
	if err != nil {
		t.Fatal(err)
	}
	m.sampleMemory = func() *uint64 { return nil }
	frames, done := monitorFrames(t, m)
	f := monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "status" })
	if f.Memory != nil {
		t.Fatal("unavailable memory was fabricated")
	}
	b.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("core close left monitor alive")
	}
	if _, err = b.NewConnectionMonitor(""); err == nil {
		t.Fatal("closed core exposed monitor")
	}
	if value := monitorResidentMemory(); value != nil && *value == 0 {
		t.Fatal("zero was presented as available RSS")
	}
}

func TestConnectionMonitorBurstAndChunkedTraffic(t *testing.T) {
	b := sr01Box(t, true)
	conns := make([]net.Conn, 1000)
	for i := range conns {
		conns[i] = monitorTestConn(t, b, "tcp", "burst.example")
	}
	defer func() {
		for _, c := range conns {
			c.Close()
		}
	}()
	m, err := b.NewConnectionMonitor("")
	if err != nil {
		t.Fatal(err)
	}
	frames, _ := monitorFrames(t, m)
	seen := make(map[string]bool)
	monitorWait(t, frames, func(f monitorFrame) bool {
		if f.Connection != nil {
			seen[f.Connection.ID] = true
		}
		return f.Type == "status"
	})
	if len(seen) != 1000 {
		t.Fatalf("snapshot lost stable IDs: %d", len(seen))
	}
	monitorWait(t, frames, func(f monitorFrame) bool {
		if f.Type != "traffic" {
			return false
		}
		if len(f.Traffic) > 128 {
			t.Fatal("traffic was not chunked")
		}
		return true
	})
	for _, c := range conns {
		c.Close()
	}
	monitorWait(t, frames, func(f monitorFrame) bool { return f.Type == "status" && f.Count == 0 })
	m.Close()
}

func TestConnectionMonitorWriterFailure(t *testing.T) {
	b := sr01Box(t, true)
	m, err := b.NewConnectionMonitor("")
	if err != nil {
		t.Fatal(err)
	}
	if err = m.stream(monitorFailWriter{}); err == nil {
		t.Fatal("writer error ignored")
	}
	m.Close()
}

type monitorFailWriter struct{}

func (monitorFailWriter) Write([]byte) (int, error) { return 0, io.ErrClosedPipe }
