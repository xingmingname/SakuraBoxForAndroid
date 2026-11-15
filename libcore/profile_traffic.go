package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"strings"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/bufio"
	N "github.com/sagernet/sing/common/network"
)

// ProfileTrafficStats counts routed application bytes, not the sum of detour hops.
// The immutable counter map is bounded by the configured tags. A connection keeps
// its admission-time attribution even if the selector changes afterwards.
type ProfileTrafficStats struct {
	counters    map[string]*profileTrafficCounter
	selectorTag string
}
type profileTrafficCounter struct{ tx, rx atomic.Int64 }
type profileTrafficValue struct {
	Tx int64 `json:"tx"`
	Rx int64 `json:"rx"`
}

func (b *BoxInstance) NewProfileTrafficStats(tags, selectorTag string) (*ProfileTrafficStats, error) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 0 || b.started || b.Box == nil {
		return nil, errors.New("profile statistics must be attached before core start")
	}
	if b.profileStats != nil {
		return nil, errors.New("profile statistics already attached")
	}
	s := &ProfileTrafficStats{counters: make(map[string]*profileTrafficCounter), selectorTag: selectorTag}
	for _, tag := range strings.Split(tags, "\n") {
		if tag != "" {
			s.counters[tag] = new(profileTrafficCounter)
		}
	}
	b.Box.Router().AppendTracker(s)
	b.profileStats = s
	return s, nil
}

// Snapshot never consumes counters and remains readable after BoxInstance.Close.
func (s *ProfileTrafficStats) Snapshot() string {
	values := make(map[string]profileTrafficValue, len(s.counters))
	for tag, c := range s.counters {
		values[tag] = profileTrafficValue{c.tx.Load(), c.rx.Load()}
	}
	data, _ := json.Marshal(values)
	return string(data)
}
func (s *ProfileTrafficStats) counter(out adapter.Outbound) *profileTrafficCounter {
	if out == nil {
		return nil
	}
	tag := out.Tag()
	if tag == s.selectorTag {
		if group, ok := out.(adapter.OutboundGroup); ok {
			tag = group.Now()
		}
	}
	return s.counters[tag]
}

func (s *ProfileTrafficStats) RoutedConnection(_ context.Context, conn net.Conn, _ adapter.InboundContext, _ adapter.Rule, out adapter.Outbound) net.Conn {
	counter := s.counter(out)
	if counter == nil {
		return conn
	}
	return bufio.NewInt64CounterConn(conn, []*atomic.Int64{&counter.tx}, []*atomic.Int64{&counter.rx})
}
func (s *ProfileTrafficStats) RoutedPacketConnection(_ context.Context, conn N.PacketConn, _ adapter.InboundContext, _ adapter.Rule, out adapter.Outbound) N.PacketConn {
	counter := s.counter(out)
	if counter == nil {
		return conn
	}
	return bufio.NewInt64CounterPacketConn(conn, []*atomic.Int64{&counter.tx}, nil, []*atomic.Int64{&counter.rx}, nil)
}
func (s *ProfileTrafficStats) RoutedFlow(_ context.Context, _ adapter.InboundContext, _ adapter.Rule, out adapter.Outbound) tun.FlowTracker {
	counter := s.counter(out)
	if counter == nil {
		return nil
	}
	return &profileTrafficFlow{tx: []*atomic.Int64{&counter.tx}, rx: []*atomic.Int64{&counter.rx}}
}

type profileTrafficFlow struct{ tx, rx []*atomic.Int64 }

func (*profileTrafficFlow) AttachFlow(tun.FlowHandle) {}
func (f *profileTrafficFlow) CountForward(n int) {
	if n > 0 {
		for _, counter := range f.tx {
			counter.Add(int64(n))
		}
	}
}
func (f *profileTrafficFlow) CountReverse(n int) {
	if n > 0 {
		for _, counter := range f.rx {
			counter.Add(int64(n))
		}
	}
}
func (*profileTrafficFlow) FlowEstablished()              {}
func (*profileTrafficFlow) CloseFlow(tun.FlowCloseReason) {}

// Preserve the legacy active-core URL Test counting scope. This client already
// exists; only wrap its existing dial result, with outbound read/write direction.
func (b *BoxInstance) urlTestClient() *http.Client {
	if b.profileStats == nil {
		var tracker adapter.ConnectionTracker
		if b.v2api != nil {
			tracker = b.v2api.StatsService()
		}
		return boxapi.CreateProxyHttpClient(b.Box, tracker)
	}
	client := boxapi.CreateProxyHttpClient(b.Box, nil)
	transport := client.Transport.(*http.Transport)
	dial := transport.DialContext
	transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		counter := b.profileStats.counter(b.Outbound().Default())
		conn, err := dial(ctx, network, address)
		if err != nil || counter == nil {
			return conn, err
		}
		return bufio.NewInt64CounterConn(conn, []*atomic.Int64{&counter.rx}, []*atomic.Int64{&counter.tx}), nil
	}
	return client
}
