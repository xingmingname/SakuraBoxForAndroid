package nakis

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type session struct {
	t          *physical
	gate       *Gate
	closed     atomic.Bool
	broken     atomic.Bool
	peerEnded  atomic.Bool
	writeEnded atomic.Bool
	udp        bool

	rMu       sync.Mutex
	wMu       sync.Mutex
	dMu       sync.Mutex
	replied   bool
	halfTimer *time.Timer
	halfUntil time.Time
}

var _ net.Conn = (*session)(nil)

func (s *session) start(ctx context.Context, hello []byte, targetPort uint16) error {
	t := s.t
	cfg := t.pool.cfg
	s.wMu.Lock()
	defer s.wMu.Unlock()
	if s.closed.Load() {
		return net.ErrClosed
	}
	first := s.preface(hello)
	plan := cfg.shape
	fill := cfg.shapeWait
	pad := len(plan) == 0
	if pad {
		plan = personaPlan(true, tlsLike(targetPort), len(first))
		fill = personaFill
	} else {
		plan = append([]Range(nil), plan...)
		plan[0].Min = max(plan[0].Min, len(first))
		plan[0].Max = max(plan[0].Max, plan[0].Min)
	}
	s.gate = NewGate(GateOptions{
		Pack: cfg.sendPack, Tail: cfg.sendTail, Quiet: cfg.sendQuiet,
		Head: Headroom, Plan: plan, Fill: fill, Pad: pad,
	}, func(wire []byte, pad int) error {
		err := t.wire.SendWirePad(wire, pad)
		if err != nil {
			s.fail()
		}
		return err
	})
	stop := context.AfterFunc(ctx, t.abort)
	if err := t.raw.SetWriteDeadline(time.Now().Add(cfg.handshakeTimeout)); err != nil {
		stop()
		return err
	}
	s.gate.Feed(first)
	if !stop() || ctx.Err() != nil {
		return ctx.Err()
	}
	if s.broken.Load() {
		return net.ErrClosed
	}
	return t.raw.SetWriteDeadline(time.Time{})
}

func (s *session) preface(hello []byte) []byte {
	wire := s.t.wire
	if wire.sealed {
		return hello
	}
	wire.sealed = true
	return append(makeProof(s.t.pool.cfg.uuid, wire.bind), hello...)
}

// Only a half-closed TCP stream has a half-close idle timer. Timer operations
// share dMu with Close so a stale callback cannot close a reused tunnel.
func (s *session) refreshHalfTimeout() {
	s.dMu.Lock()
	defer s.dMu.Unlock()
	if s.closed.Load() || s.broken.Load() || s.udp {
		return
	}
	if s.peerEnded.Load() && s.writeEnded.Load() {
		s.stopHalfTimer()
		return
	}
	timeout := s.t.pool.cfg.halfCloseTimeout
	if timeout == 0 {
		timeout = 2 * time.Minute
	}
	s.halfUntil = time.Now().Add(timeout)
	if s.halfTimer == nil {
		s.halfTimer = time.AfterFunc(timeout, s.expireHalf)
	} else {
		s.halfTimer.Reset(timeout)
	}
}

func (s *session) expireHalf() {
	s.dMu.Lock()
	if s.closed.Load() || s.halfTimer == nil || time.Now().Before(s.halfUntil) {
		s.dMu.Unlock()
		return
	}
	s.broken.Store(true)
	s.stopHalfTimer()
	s.dMu.Unlock()
	s.t.abortSession(s)
}

func (s *session) stopHalfTimer() {
	if s.halfTimer != nil {
		s.halfTimer.Stop()
		s.halfTimer = nil
	}
}

func (s *session) fail() {
	s.broken.Store(true)
	s.dMu.Lock()
	s.stopHalfTimer()
	s.dMu.Unlock()
	s.t.abortSession(s)
}

func (s *session) readReply() error {
	if s.replied {
		return nil
	}
	err := s.t.wire.ReadReply()
	if err == nil {
		s.replied = true
	}
	return err
}

func (s *session) Read(buf []byte) (int, error) {
	if len(buf) == 0 {
		return 0, nil
	}
	s.rMu.Lock()
	defer s.rMu.Unlock()
	if s.closed.Load() {
		return 0, net.ErrClosed
	}
	if s.peerEnded.Load() {
		return 0, io.EOF
	}
	if err := s.readReply(); err != nil {
		s.fail()
		return 0, err
	}
	n, err := s.t.wire.Read(buf)
	if errors.Is(err, ErrNap) {
		s.peerEnded.Store(true)
		s.refreshHalfTimeout()
		return n, io.EOF
	}
	if err != nil {
		if !s.udp || n != 0 || !readDeadline(err) {
			s.fail()
		}
	} else if n > 0 && s.writeEnded.Load() {
		s.refreshHalfTimeout()
	}
	return n, err
}

func (s *session) Write(buf []byte) (int, error) {
	s.wMu.Lock()
	defer s.wMu.Unlock()
	written := 0
	for len(buf) > 0 {
		if s.closed.Load() || s.broken.Load() || s.writeEnded.Load() {
			return written, net.ErrClosed
		}
		n := min(len(buf), PackCap)
		s.gate.Feed(buf[:n])
		if s.broken.Load() {
			return written, net.ErrClosed
		}
		written += n
		buf = buf[n:]
	}
	if written > 0 && s.peerEnded.Load() {
		s.refreshHalfTimeout()
	}
	return written, nil
}

func (s *session) CloseWrite() error {
	s.wMu.Lock()
	defer s.wMu.Unlock()
	if s.closed.Load() || s.broken.Load() {
		return net.ErrClosed
	}
	if s.writeEnded.Load() {
		return nil
	}
	s.refreshHalfTimeout()
	s.gate.Flush()
	s.gate.Close()
	if s.broken.Load() {
		return net.ErrClosed
	}
	if err := s.t.wire.Nap(); err != nil {
		s.fail()
		return err
	}
	s.writeEnded.Store(true)
	s.refreshHalfTimeout()
	return nil
}

func (s *session) Close() error {
	return s.close(nil, time.Time{})
}

// UDP starts the deadline before flushing its queue. The same timer protects
// the NAP exchange and is stopped before the tunnel can return to the pool.
func (s *session) close(guard *time.Timer, deadline time.Time) error {
	s.dMu.Lock()
	if !s.closed.CompareAndSwap(false, true) {
		s.dMu.Unlock()
		if guard != nil && !guard.Stop() {
			s.t.abort()
		}
		return nil
	}
	s.stopHalfTimer()
	t := s.t
	if guard == nil {
		window := t.pool.drainWindow()
		deadline = time.Now().Add(window)
		// Some detours accept deadlines without interrupting I/O.
		guard = time.AfterFunc(window, t.abort)
	}
	s.dMu.Unlock()
	if t.raw.SetDeadline(deadline) != nil {
		s.fail()
	}

	// Keep the call responsive while the two peers exchange their NAPs.
	if !t.pool.begin() {
		t.abort()
		s.finish(guard)
		return nil
	}
	go func() {
		defer t.pool.work.Done()
		s.finish(guard)
	}()
	return nil
}

func (s *session) finish(guard *time.Timer) {
	defer guard.Stop()
	t := s.t
	s.wMu.Lock()
	if s.gate != nil {
		if !s.broken.Load() && !s.writeEnded.Load() && t.pool.ctx.Err() == nil {
			s.gate.Flush()
		}
		s.gate.Close()
	}
	s.wMu.Unlock()
	if s.broken.Load() || t.aborting() || t.pool.ctx.Err() != nil || (!s.writeEnded.Load() && t.wire.Nap() != nil) {
		t.abort()
		return
	}
	s.rMu.Lock()
	defer s.rMu.Unlock()
	if !s.peerEnded.Load() && t.wire.drain(s.replied) != nil {
		t.abort()
		return
	}
	if !guard.Stop() || t.raw.SetDeadline(time.Time{}) != nil {
		t.abort()
		return
	}
	t.pool.put(t)
}

func (s *session) LocalAddr() net.Addr  { return s.t.raw.LocalAddr() }
func (s *session) RemoteAddr() net.Addr { return s.t.raw.RemoteAddr() }

func (s *session) setDeadline(set func(time.Time) error, at time.Time) error {
	s.dMu.Lock()
	defer s.dMu.Unlock()
	if s.closed.Load() {
		return net.ErrClosed
	}
	return set(at)
}

func (s *session) SetDeadline(at time.Time) error {
	return s.setDeadline(s.t.raw.SetDeadline, at)
}

func (s *session) SetReadDeadline(at time.Time) error {
	return s.setDeadline(s.t.raw.SetReadDeadline, at)
}

func (s *session) SetWriteDeadline(at time.Time) error {
	return s.setDeadline(s.t.raw.SetWriteDeadline, at)
}
