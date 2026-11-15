package nakis

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type tunnelPool struct {
	cfg    clientConfig
	dialer N.Dialer
	tls    *tls.Config
	ctx    context.Context
	cancel context.CancelFunc

	mu           sync.Mutex
	closed       bool
	warming      bool
	live         map[*physical]struct{}
	idle         []*idleTunnel
	work         sync.WaitGroup
	kick         chan struct{}
	rotation     atomic.Uint64
	handshakeAvg atomic.Int64
}

type physical struct {
	pool    *tunnelPool
	raw     net.Conn
	release func()
	done    chan struct{}
	dead    atomic.Bool
	wire    *Tunnel
	once    sync.Once
	uses    int
	life    lifespan
	// Guarded by pool.mu, set only for a checked-out logical connection.
	session *session
}

type idleTunnel struct {
	tunnel   *physical
	expires  time.Time
	watching chan struct{}
	checked  chan bool
}

func newPool(ctx context.Context, cfg clientConfig, d N.Dialer, tlsCfg *tls.Config) *tunnelPool {
	ctx, cancel := context.WithCancel(ctx)
	return &tunnelPool{
		cfg: cfg, dialer: d, tls: tlsCfg, ctx: ctx, cancel: cancel,
		live: make(map[*physical]struct{}), kick: make(chan struct{}, 1),
	}
}

func (p *tunnelPool) observeHandshake(elapsed time.Duration) {
	for old := p.handshakeAvg.Load(); ; old = p.handshakeAvg.Load() {
		next := int64(elapsed)
		if old != 0 {
			next = old + (next-old)/8
		}
		if p.handshakeAvg.CompareAndSwap(old, next) {
			return
		}
	}
}

func (p *tunnelPool) drainWindow() time.Duration {
	average := min(time.Duration(p.handshakeAvg.Load()), 2500*time.Millisecond)
	return max(500*time.Millisecond, 2*average)
}

func (p *tunnelPool) begin() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed || p.ctx.Err() != nil {
		return false
	}
	p.work.Add(1)
	return true
}

func (p *tunnelPool) close() {
	p.mu.Lock()
	p.closed = true
	p.cancel()
	all := make([]*physical, 0, len(p.live))
	sessions := make([]*session, 0, len(p.live))
	for t := range p.live {
		all = append(all, t)
		if t.session != nil {
			sessions = append(sessions, t.session)
		}
	}
	p.idle = nil
	p.mu.Unlock()
	for _, t := range all {
		t.abort()
	}
	for _, s := range sessions {
		_ = s.Close()
	}
	p.work.Wait()
}

func (t *physical) aborting() bool { return t.dead.Load() }

// A packet reader can report an error after the worker has finished a lease.
// A stale reader must never invalidate the next lease of the same tunnel.
func (t *physical) abortSession(s *session) {
	t.pool.mu.Lock()
	ownsTunnel := t.session == s
	if ownsTunnel {
		t.dead.Store(true)
	}
	t.pool.mu.Unlock()
	if ownsTunnel {
		t.abort()
	}
}

func (t *physical) abort() {
	t.dead.Store(true)
	t.once.Do(func() {
		if t.done != nil {
			close(t.done)
		}
		if stream, ok := t.raw.(interface{ Abort() error }); ok {
			_ = stream.Abort()
		} else {
			_ = t.raw.Close()
		}
		if t.release != nil {
			t.release()
		}
		t.pool.mu.Lock()
		delete(t.pool.live, t)
		for i, item := range t.pool.idle {
			if item.tunnel == t {
				copy(t.pool.idle[i:], t.pool.idle[i+1:])
				t.pool.idle[len(t.pool.idle)-1] = nil
				t.pool.idle = t.pool.idle[:len(t.pool.idle)-1]
				break
			}
		}
		t.pool.mu.Unlock()
	})
}

func (p *tunnelPool) open(ctx context.Context, dst M.Socksaddr) (*physical, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	started := time.Now()
	// Some detours bind the returned stream to the context passed to DialContext.
	// Apply the dial timeout during setup, then give the successful stream the
	// lifetime of this physical tunnel instead of the caller's dial attempt.
	dialCtx, cancelDial := context.WithTimeout(ctx, p.cfg.dialTimeout)
	leaseCtx, cancelLease := context.WithCancel(context.WithoutCancel(ctx))
	stopPool := context.AfterFunc(p.ctx, cancelLease)
	release := func() {
		stopPool()
		cancelLease()
	}
	stopDial := context.AfterFunc(dialCtx, cancelLease)
	raw, err := p.dialer.DialContext(leaseCtx, N.NetworkTCP, dst)
	detached := stopDial()
	dialErr := dialCtx.Err()
	cancelDial()
	if err == nil {
		switch {
		case dialErr != nil:
			err = dialErr
		case !detached || leaseCtx.Err() != nil:
			err = net.ErrClosed
		}
	}
	if err != nil {
		release()
		if raw != nil {
			_ = raw.Close()
		}
		return nil, fmt.Errorf("nakis transport: %w", err)
	}
	if stream, ok := raw.(interface{ EnableStreamCancellation() }); ok {
		stream.EnableStreamCancellation()
	}
	t := &physical{pool: p, raw: raw, release: release, done: make(chan struct{})}
	p.mu.Lock()
	if p.closed || ctx.Err() != nil {
		p.mu.Unlock()
		t.abort()
		return nil, errClosed
	}
	p.live[t] = struct{}{}
	p.mu.Unlock()

	handshake, cancel := context.WithTimeout(ctx, p.cfg.handshakeTimeout)
	stopHandshake := context.AfterFunc(handshake, t.abort)
	t.wire, err = secure(handshake, raw, p.tls)
	if stopped := stopHandshake(); err == nil && (!stopped || handshake.Err() != nil) {
		err = handshake.Err()
	}
	cancel()
	if err != nil {
		t.abort()
		return nil, err
	}
	p.observeHandshake(time.Since(started))
	return t, nil
}

func (p *tunnelPool) race(ctx context.Context) (*physical, error) {
	count := len(p.cfg.servers)
	if count == 1 {
		return p.open(ctx, p.cfg.servers[0])
	}
	start := int((p.rotation.Add(1) - 1) % uint64(count))
	width := min(p.cfg.raceCount, count)
	var err error
	for attempted := 0; attempted < count; attempted += width {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		t, raceErr := p.raceBatch(ctx, (start+attempted)%count, min(width, count-attempted))
		if raceErr == nil {
			return t, nil
		}
		err = raceErr
	}
	return nil, err
}

func (p *tunnelPool) raceBatch(ctx context.Context, start, width int) (*physical, error) {
	ctx, cancel := context.WithCancel(ctx)
	type result struct {
		t   *physical
		err error
	}
	results := make(chan result, width)
	for i := range width {
		dst := p.cfg.servers[(start+i)%len(p.cfg.servers)]
		go func() {
			if i >= 2 {
				delay := time.NewTimer(time.Duration(i-1) * 50 * time.Millisecond)
				defer delay.Stop()
				select {
				case <-ctx.Done():
					results <- result{err: ctx.Err()}
					return
				case <-delay.C:
				}
			}
			t, err := p.open(ctx, dst)
			results <- result{t, err}
		}()
	}
	var last error
	for i := range width {
		r := <-results
		if r.err == nil {
			cancel()
			// The parent Dial or maintainer still owns a work reference.
			p.work.Add(1)
			go func() {
				defer p.work.Done()
				for j := i + 1; j < width; j++ {
					if other := <-results; other.t != nil {
						other.t.abort()
					}
				}
			}()
			return r.t, nil
		}
		last = r.err
	}
	cancel()
	return nil, last
}

func (p *tunnelPool) dial(ctx context.Context, dst M.Socksaddr) (net.Conn, error) {
	if dst.Port == 0 {
		return nil, errors.New("nakis: target port is required")
	}
	return p.lease(ctx, dst.AddrString(), dst.Port, false)
}

func (p *tunnelPool) lease(ctx context.Context, host string, port uint16, udp bool) (*session, error) {
	if !p.begin() {
		return nil, errClosed
	}
	defer p.work.Done()
	ctx, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(p.ctx, cancel)
	defer stop()
	defer cancel()
	hello, err := HelloBytes(host, port)
	if err != nil {
		return nil, err
	}
	t := p.take(ctx)
	if t == nil {
		t, err = p.race(ctx)
		if err != nil {
			return nil, err
		}
	}
	s := &session{t: t, udp: udp}
	p.mu.Lock()
	if p.closed || ctx.Err() != nil || t.aborting() {
		p.mu.Unlock()
		t.abort()
		return nil, errClosed
	}
	if t.uses == 0 {
		t.life = newLife(p.cfg.poolMaxUses)
	}
	t.uses++
	t.session = s
	p.mu.Unlock()
	if udp {
		err = s.startUDP(ctx, hello)
	} else {
		err = s.start(ctx, hello, port)
	}
	if err != nil {
		t.abort()
		_ = s.Close()
		return nil, err
	}
	p.startWarm()
	return s, nil
}

func (p *tunnelPool) take(ctx context.Context) *physical {
	for ctx.Err() == nil {
		p.mu.Lock()
		if len(p.idle) == 0 {
			p.mu.Unlock()
			return nil
		}
		last := len(p.idle) - 1
		item := p.idle[last]
		p.idle[last] = nil
		p.idle = p.idle[:last]
		p.mu.Unlock()
		if !item.tunnel.aborting() && time.Now().Before(item.expires) && item.take(ctx) && !item.tunnel.aborting() {
			return item.tunnel
		}
		item.tunnel.abort()
	}
	return nil
}

func (it *idleTunnel) take(ctx context.Context) bool {
	timer := time.NewTimer(100 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-it.watching:
	case <-timer.C:
		return false
	case <-ctx.Done():
		return false
	}
	// Let an idle reader inspect already queued transport data before waking
	// it with a deadline. A failed or expired probe forces a cold dial.
	deadline := time.Now().Add(2 * time.Millisecond)
	if it.expires.Before(deadline) {
		deadline = it.expires
	}
	if it.tunnel.raw.SetReadDeadline(deadline) != nil {
		return false
	}
	select {
	case ok := <-it.checked:
		return ok && time.Now().Before(it.expires) && it.tunnel.raw.SetReadDeadline(time.Time{}) == nil
	case <-timer.C:
		// Some detour transports may accept a deadline without waking the read.
		return false
	case <-ctx.Done():
		return false
	}
}

func (p *tunnelPool) put(t *physical) {
	if t.life.spent(t.uses, t.wire.Moved()) {
		t.abort()
		return
	}
	p.mu.Lock()
	_, tracked := p.live[t]
	if p.closed || !tracked || t.aborting() || len(p.idle) >= p.cfg.poolHi {
		p.mu.Unlock()
		t.abort()
		return
	}
	t.session = nil
	item := &idleTunnel{
		tunnel: t, expires: time.Now().Add(p.cfg.poolMaxAge),
		watching: make(chan struct{}), checked: make(chan bool, 1),
	}
	if t.raw.SetReadDeadline(item.expires) != nil || t.aborting() {
		p.mu.Unlock()
		t.abort()
		return
	}
	p.work.Add(1)
	go func() {
		defer p.work.Done()
		ok := t.wire.idleClean(item.watching) && time.Now().Before(item.expires)
		item.checked <- ok
		if !ok {
			t.abort()
		}
	}()
	p.idle = append(p.idle, item)
	p.mu.Unlock()
}

func (p *tunnelPool) startWarm() {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	if !p.warming {
		p.warming = true
		p.work.Add(1)
		go p.maintain()
	}
	p.mu.Unlock()
	select {
	case p.kick <- struct{}{}:
	default:
	}
}

func (p *tunnelPool) sweep() {
	p.mu.Lock()
	var expired []*physical
	keep := p.idle[:0]
	for _, entry := range p.idle {
		_, tracked := p.live[entry.tunnel]
		if !tracked || entry.tunnel.aborting() || time.Now().After(entry.expires) {
			expired = append(expired, entry.tunnel)
		} else {
			keep = append(keep, entry)
		}
	}
	clear(p.idle[len(keep):])
	p.idle = keep
	p.mu.Unlock()
	for _, t := range expired {
		t.abort()
	}
}

func (p *tunnelPool) maintain() {
	defer p.work.Done()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	// The reference client limits concurrent prewarming dials, not the
	// number of active tunnels and not the configured idle capacity.
	const warmConcurrency = 16
	completed := make(chan bool, warmConcurrency)
	var flying, fails int
	var retry time.Time
	for {
		select {
		case <-p.ctx.Done():
			return
		case <-ticker.C:
		case <-p.kick:
		case ok := <-completed:
			flying--
			if !ok {
				fails++
				retry = time.Now().Add(500 * time.Millisecond << min(fails, 6))
			} else {
				fails = 0
			}
			// Do not immediately refill a tunnel the idle watcher may be discarding.
			continue
		}
		p.sweep()
		if p.ctx.Err() != nil || time.Now().Before(retry) {
			continue
		}
		p.mu.Lock()
		active := max(0, len(p.live)-len(p.idle)-flying)
		goal := min(p.cfg.poolHi, p.cfg.poolLo+active/2)
		need := min(goal-len(p.idle)-flying, warmConcurrency-flying)
		p.mu.Unlock()
		for range need {
			flying++
			p.work.Add(1)
			go func() {
				defer p.work.Done()
				index := int((p.rotation.Add(1) - 1) % uint64(len(p.cfg.servers)))
				t, err := p.open(p.ctx, p.cfg.servers[index])
				if err == nil {
					p.put(t)
				}
				completed <- err == nil
			}()
		}
	}
}
