package libcore

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing/common/memory"
	"github.com/sagernet/sing/common/observable"
)

type monitorConnection struct {
	ID          string   `json:"id"`
	Domain      string   `json:"domain,omitempty"`
	Destination string   `json:"destination,omitempty"`
	Port        uint16   `json:"port"`
	Source      string   `json:"source,omitempty"`
	SourcePort  uint16   `json:"sourcePort"`
	Network     string   `json:"network"`
	CreatedAt   int64    `json:"createdAt"`
	Duration    int64    `json:"duration"`
	Upload      int64    `json:"upload"`
	Download    int64    `json:"download"`
	Outbound    string   `json:"outbound,omitempty"`
	Chain       []string `json:"chain,omitempty"`
	Rule        string   `json:"rule,omitempty"`
	OwnerUID    *int32   `json:"ownerUid,omitempty"`
	Packages    []string `json:"packages,omitempty"`
}

type monitorTraffic struct {
	ID       string `json:"id"`
	Upload   int64  `json:"upload"`
	Download int64  `json:"download"`
	Duration int64  `json:"duration"`
}

type monitorFrame struct {
	Type       string             `json:"type"`
	Count      int                `json:"count,omitempty"`
	Memory     *uint64            `json:"memory,omitempty"`
	Connection *monitorConnection `json:"connection,omitempty"`
	ID         string             `json:"id,omitempty"`
	ClosedAt   int64              `json:"closedAt,omitempty"`
	Traffic    []monitorTraffic   `json:"traffic,omitempty"`
}

// ConnectionMonitor is a UI-scoped stream of the existing traffic manager.
// It does not start a listener, resolve names, or change routing.
type ConnectionMonitor struct {
	manager      *trafficcontrol.Manager
	ctx          context.Context
	cancel       context.CancelFunc
	events       observable.Subscription[trafficcontrol.ConnectionEvent]
	done         <-chan struct{}
	filter       string
	once         sync.Once
	writing      atomic.Bool
	access       sync.Mutex
	file         *os.File
	closed       bool
	sampleMemory func() *uint64
}

func (b *BoxInstance) NewConnectionMonitor(connectionID string) (*ConnectionMonitor, error) {
	if connectionID != "" {
		if _, err := uuid.FromString(connectionID); err != nil {
			return nil, errors.New("invalid connection ID")
		}
	}
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || !b.started || b.trafficTracker == nil || b.ctx.Err() != nil {
		return nil, errors.New("connection tracking unavailable")
	}
	events, done, err := b.trafficTracker.SubscribeEvents()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(b.ctx)
	return &ConnectionMonitor{
		manager: b.trafficTracker, ctx: ctx, cancel: cancel, events: events,
		done: done, filter: connectionID, sampleMemory: monitorResidentMemory,
	}, nil
}

func monitorResidentMemory() *uint64 {
	// Same source as sing-box daemon.readStatus (used by SFA), not Go heap.
	if !memory.TotalAvailable() {
		return nil
	}
	value := memory.Total()
	if value == 0 {
		return nil
	}
	return &value
}

// WriteToFD takes ownership of an Android pipe write descriptor.
func (m *ConnectionMonitor) WriteToFD(fd int32) error {
	if fd < 0 {
		return os.ErrInvalid
	}
	file, err := monitorOpenPipe(fd)
	if err != nil {
		return err
	}
	defer file.Close()
	if !m.writing.CompareAndSwap(false, true) {
		return errors.New("monitor already streaming")
	}
	defer m.Close()
	m.access.Lock()
	if m.closed {
		m.access.Unlock()
		return os.ErrClosed
	}
	m.file = file
	m.access.Unlock()
	// Closing the core must also interrupt a backpressured pipe write.
	stop := context.AfterFunc(m.ctx, func() { m.closeFile() })
	defer stop()
	return m.stream(monitorPipeWriter{file})
}

type monitorPipeWriter struct{ file *os.File }

func (w monitorPipeWriter) Write(data []byte) (int, error) {
	if err := w.file.SetWriteDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return 0, err
	}
	return w.file.Write(data)
}

func (m *ConnectionMonitor) closeFile() {
	m.access.Lock()
	file := m.file
	m.access.Unlock()
	if file != nil {
		_ = file.Close()
	}
}

func (m *ConnectionMonitor) Close() {
	m.once.Do(func() {
		m.access.Lock()
		m.closed = true
		m.access.Unlock()
		m.cancel()
		m.closeFile()
		m.manager.UnSubscribeEvents(m.events)
	})
}

func monitorMetadata(c *trafficcontrol.TrackerMetadata, end time.Time, details bool) *monitorConnection {
	meta := c.Metadata
	// Prefer recorded original targets, then confirmed sniff/DNS metadata.
	domain := meta.RouteOriginalDestination.Fqdn
	if domain == "" {
		domain = meta.OriginDestination.Fqdn
	}
	if domain == "" {
		domain = meta.Destination.Fqdn
	}
	if domain == "" {
		domain = meta.Domain
	}
	destination, source := "", ""
	if meta.Destination.Addr.IsValid() {
		destination = meta.Destination.Addr.String()
	}
	if meta.Source.Addr.IsValid() {
		source = meta.Source.Addr.String()
	}
	var rule string
	var packages, chain []string
	var ownerUID *int32
	if owner, ok := dashboardOwner(meta); ok {
		ownerUID = &owner.UID
		packages = []string{owner.Package}
	}
	if details {
		rule = meta.RouteRule
		if rule == "" && c.Rule != nil {
			rule = c.Rule.String()
		}
		if meta.ProcessInfo != nil {
			packages = append([]string(nil), meta.ProcessInfo.AndroidPackageNames...)
		}
		chain = append([]string(nil), c.Chain...)
	}
	duration := time.Since(c.CreatedAt)
	if !end.IsZero() {
		duration = end.Sub(c.CreatedAt)
	}
	return &monitorConnection{
		ID: c.ID.String(), Domain: domain, Destination: destination,
		Port: meta.Destination.Port, Source: source, SourcePort: meta.Source.Port,
		Network: meta.Network, CreatedAt: c.CreatedAt.UnixMilli(),
		Duration: max(0, duration.Milliseconds()), Upload: c.Upload.Load(), Download: c.Download.Load(),
		Outbound: c.Outbound, Chain: chain, Rule: rule, OwnerUID: ownerUID, Packages: packages,
	}
}

func (m *ConnectionMonitor) stream(output io.Writer) error {
	writer := bufio.NewWriterSize(output, 32*1024)
	encoder := json.NewEncoder(writer)
	write := func(frame monitorFrame) error { return encoder.Encode(frame) }
	known := make(map[uuid.UUID]struct{})
	wanted := func(id uuid.UUID) bool { return m.filter == "" || id.String() == m.filter }
	memoryValue := m.sampleMemory()
	status := func() error {
		if err := write(monitorFrame{Type: "status", Count: m.manager.ConnectionsLen(), Memory: memoryValue}); err != nil {
			return err
		}
		return writer.Flush()
	}
	add := func(c *trafficcontrol.TrackerMetadata) error {
		if !wanted(c.ID) {
			return nil
		}
		known[c.ID] = struct{}{}
		return write(monitorFrame{Type: "connection", Connection: monitorMetadata(c, time.Time{}, m.filter != "")})
	}
	closed := func(c *trafficcontrol.TrackerMetadata, id uuid.UUID, at time.Time) error {
		delete(known, id)
		if !wanted(id) {
			return nil
		}
		frame := monitorFrame{Type: "closed", ID: id.String()}
		if c != nil {
			frame.Connection = monitorMetadata(c, at, m.filter != "")
		}
		if !at.IsZero() {
			frame.ClosedAt = at.UnixMilli()
		}
		return write(frame)
	}
	if err := write(monitorFrame{Type: "reset"}); err != nil {
		return err
	}
	for _, c := range m.manager.Connections() {
		if err := add(c); err != nil {
			return err
		}
	}
	if m.filter != "" && len(known) == 0 {
		for _, c := range m.manager.ClosedConnections() {
			if c.ID.String() == m.filter {
				if err := closed(c, c.ID, c.ClosedAt); err != nil {
					return err
				}
				break
			}
		}
	}
	if err := status(); err != nil {
		return err
	}
	heartbeat := time.NewTicker(time.Second)
	flush := time.NewTicker(100 * time.Millisecond)
	defer heartbeat.Stop()
	defer flush.Stop()
	dirty := false
	for {
		select {
		case <-m.ctx.Done():
			return m.ctx.Err()
		case <-m.done:
			return nil
		case event := <-m.events:
			if !wanted(event.ID) {
				dirty = true // global count still changes in a filtered detail.
				continue
			}
			switch event.Type {
			case trafficcontrol.ConnectionEventNew:
				// A closed connection can already be in the initial snapshot queue.
				if m.manager.Connection(event.ID) != nil {
					if _, exists := known[event.ID]; !exists {
						if err := add(event.Metadata); err != nil {
							return err
						}
					}
				}
			case trafficcontrol.ConnectionEventClosed:
				if err := closed(event.Metadata, event.ID, event.ClosedAt); err != nil {
					return err
				}
			}
			dirty = true
		case <-flush.C:
			if dirty {
				if err := status(); err != nil {
					return err
				}
				dirty = false
			}
		case <-heartbeat.C:
			memoryValue = m.sampleMemory()
			active := m.manager.Connections()
			current := make(map[uuid.UUID]struct{}, len(active))
			batch := make([]monitorTraffic, 0, 128)
			sendBatch := func() error {
				if len(batch) == 0 {
					return nil
				}
				err := write(monitorFrame{Type: "traffic", Traffic: batch})
				batch = batch[:0]
				return err
			}
			for _, c := range active {
				if !wanted(c.ID) {
					continue
				}
				current[c.ID] = struct{}{}
				if _, exists := known[c.ID]; !exists {
					if err := add(c); err != nil {
						return err
					}
				}
				batch = append(batch, monitorTraffic{
					ID: c.ID.String(), Upload: c.Upload.Load(), Download: c.Download.Load(),
					Duration: max(0, time.Since(c.CreatedAt).Milliseconds()),
				})
				if len(batch) == cap(batch) {
					if err := sendBatch(); err != nil {
						return err
					}
				}
			}
			if err := sendBatch(); err != nil {
				return err
			}
			// The upstream event queue is bounded and may drop bursts. Reconcile
			// IDs each second so a dropped close never leaves an active ghost.
			var missing []uuid.UUID
			for id := range known {
				if _, exists := current[id]; !exists {
					missing = append(missing, id)
				}
			}
			if len(missing) != 0 {
				recent := make(map[uuid.UUID]*trafficcontrol.TrackerMetadata)
				for _, c := range m.manager.ClosedConnections() {
					recent[c.ID] = c
				}
				for _, id := range missing {
					c := recent[id]
					var at time.Time
					if c != nil {
						at = c.ClosedAt
					}
					if err := closed(c, id, at); err != nil {
						return err
					}
				}
			}
			if err := status(); err != nil {
				return err
			}
			dirty = false
		}
	}
}
