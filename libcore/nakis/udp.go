// Nakis UoT wire format adapted from n-yarvex/nakis at
// 33eb35178ad6c6774144baf562f589d612578603 (MIT, see LICENSE).
package nakis

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const (
	uotHost       = "udp.nakis.arpa"
	maxUDPPayload = 65535
	udpQueueLimit = 1 << 20
	udpBatchLimit = 32 << 10
)

var errUDPRecord = errors.New("nakis: invalid UoT datagram")

func (s *session) startUDP(ctx context.Context, hello []byte) error {
	ctx, cancel := context.WithTimeout(ctx, s.t.pool.cfg.handshakeTimeout)
	defer cancel()
	stop := context.AfterFunc(ctx, s.t.abort)
	defer stop()
	s.wMu.Lock()
	defer s.wMu.Unlock()
	if s.closed.Load() {
		return net.ErrClosed
	}
	deadline, _ := ctx.Deadline()
	if err := s.t.raw.SetDeadline(deadline); err != nil {
		return err
	}
	payload := s.preface(hello)
	wire := GetBuf(Headroom + len(payload))
	defer PutBuf(wire)
	copy(wire[Headroom:], payload)
	if err := s.t.wire.SendWirePad(wire, 4+rand.IntN(400)); err != nil {
		return fmt.Errorf("nakis: send UoT association: %w", err)
	}
	// Only return a PacketConn after the remote peer accepts the association.
	s.rMu.Lock()
	err := s.readReply()
	s.rMu.Unlock()
	if err != nil {
		if ctx.Err() != nil {
			return fmt.Errorf("nakis: UoT association timed out or canceled: %w", ctx.Err())
		}
		return fmt.Errorf("nakis: UoT association rejected (server must support and enable Nakis UDP): %w", err)
	}
	if !stop() || ctx.Err() != nil {
		return ctx.Err()
	}
	return s.t.raw.SetDeadline(time.Time{})
}

type udpPacketConn struct {
	session *session
	reader  *bufio.Reader
	readMu  sync.Mutex

	mu            sync.Mutex
	queue         [][]byte
	bytes         int
	closed        bool
	writeDeadline time.Time
	closeGuard    *time.Timer
	closeDeadline time.Time
	wake          chan struct{}
	closing       chan struct{}
}

var (
	_ net.PacketConn  = (*udpPacketConn)(nil)
	_ N.NetPacketConn = (*udpPacketConn)(nil)
)

func (p *tunnelPool) listenPacket(ctx context.Context) (net.PacketConn, error) {
	s, err := p.lease(ctx, uotHost, 0, true)
	if err != nil {
		return nil, err
	}
	if !p.begin() {
		_ = s.Close()
		return nil, errClosed
	}
	c := &udpPacketConn{
		session: s, reader: bufio.NewReaderSize(s, 4096),
		wake: make(chan struct{}, 1), closing: make(chan struct{}),
	}
	go c.send()
	return c, nil
}

func (c *udpPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, addr, err := c.readDatagram(p)
	if err != nil {
		return 0, nil, err
	}
	if addr.IsIP() {
		return n, addr.UDPAddr(), nil
	}
	return n, addr, nil
}

func (c *udpPacketConn) ReadPacket(b *buf.Buffer) (M.Socksaddr, error) {
	n, addr, err := c.readDatagram(b.FreeBytes())
	if err == nil {
		b.Extend(n)
	}
	return addr, err
}

func (c *udpPacketConn) WritePacket(b *buf.Buffer, addr M.Socksaddr) error {
	defer b.Release()
	_, err := c.WriteTo(b.Bytes(), addr)
	return err
}

func (c *udpPacketConn) readDatagram(p []byte) (int, M.Socksaddr, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()
	select {
	case <-c.closing:
		return 0, M.Socksaddr{}, net.ErrClosed
	default:
	}
	if _, err := c.reader.Peek(1); err != nil {
		if !readDeadline(err) {
			_ = c.Close()
		}
		return 0, M.Socksaddr{}, err
	}
	address, err := M.SocksaddrSerializer.ReadAddrPort(c.reader)
	if err != nil || !address.IsValid() || address.Port == 0 {
		return c.badRecord(err)
	}
	var size [2]byte
	if _, err := io.ReadFull(c.reader, size[:]); err != nil {
		return c.badRecord(err)
	}
	payload := int(binary.BigEndian.Uint16(size[:]))
	n, err := io.ReadFull(c.reader, p[:min(payload, len(p))])
	if err != nil {
		return c.badRecord(err)
	}
	if n < payload {
		if _, err := io.CopyN(io.Discard, c.reader, int64(payload-n)); err != nil {
			return c.badRecord(err)
		}
	}
	return n, address, nil
}

func (c *udpPacketConn) badRecord(cause error) (int, M.Socksaddr, error) {
	c.session.fail()
	_ = c.Close()
	if cause != nil {
		return 0, M.Socksaddr{}, fmt.Errorf("%w: %w", errUDPRecord, cause)
	}
	return 0, M.Socksaddr{}, errUDPRecord
}

func (c *udpPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if addr == nil || len(p) > maxUDPPayload {
		return 0, errUDPRecord
	}
	destination := M.SocksaddrFromNet(addr).Unwrap()
	if !destination.IsValid() || destination.Port == 0 || destination.Addr.Zone() != "" ||
		len(destination.Fqdn) > 255 || strings.EqualFold(destination.Fqdn, uotHost) {
		return 0, errUDPRecord
	}
	size := M.SocksaddrSerializer.AddrPortLen(destination) + 2 + len(p)
	c.mu.Lock()
	defer c.mu.Unlock()
	select {
	case <-c.session.t.done:
		return 0, net.ErrClosed
	default:
	}
	if c.closed || c.session.peerEnded.Load() {
		return 0, net.ErrClosed
	}
	if !c.writeDeadline.IsZero() && !time.Now().Before(c.writeDeadline) {
		return 0, os.ErrDeadlineExceeded
	}
	if c.bytes+size > udpQueueLimit {
		// Like upstream, drop this datagram. A write error ends the entire
		// sing-box packet copy, including other destinations on this lease.
		return len(p), nil
	}
	frame := bytes.NewBuffer(make([]byte, 0, size))
	if err := M.SocksaddrSerializer.WriteAddrPort(frame, destination); err != nil {
		return 0, err
	}
	var length [2]byte
	binary.BigEndian.PutUint16(length[:], uint16(len(p)))
	frame.Write(length[:])
	frame.Write(p)
	c.queue = append(c.queue, frame.Bytes())
	c.bytes += frame.Len()
	select {
	case c.wake <- struct{}{}:
	default:
	}
	return len(p), nil
}

func (c *udpPacketConn) send() {
	s := c.session
	defer s.t.pool.work.Done()
	defer func() {
		c.mu.Lock()
		if !c.closed {
			c.closed = true
			close(c.closing)
		}
		guard, deadline := c.closeGuard, c.closeDeadline
		c.queue = nil
		c.bytes = 0
		c.mu.Unlock()
		_ = s.close(guard, deadline)
	}()
	for {
		select {
		case <-s.t.done:
			return
		case <-c.closing:
		case <-c.wake:
			if jitter := s.t.pool.cfg.udpJitter; jitter > 0 {
				timer := time.NewTimer(time.Duration(rand.Int64N(int64(jitter))) + time.Microsecond)
				select {
				case <-timer.C:
				case <-c.closing:
				case <-s.t.done:
				}
				timer.Stop()
			}
		}
		for {
			select {
			case <-s.t.done:
				return
			default:
			}
			if s.peerEnded.Load() {
				return
			}
			batch, closing := c.takeBatch()
			if len(batch) == 0 {
				if closing {
					return
				}
				break
			}
			if err := s.t.wire.Send(nil, batch); err != nil {
				s.fail()
				return
			}
		}
	}
}

func (c *udpPacketConn) takeBatch() ([]byte, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.queue) == 0 {
		return nil, c.closed
	}
	batch := c.queue[0]
	count := 1
	for _, frame := range c.queue[1:] {
		if len(batch)+len(frame) > udpBatchLimit {
			break
		}
		batch = append(batch, frame...)
		count++
	}
	c.bytes -= len(batch)
	clear(c.queue[:count])
	c.queue = c.queue[count:]
	if len(c.queue) == 0 {
		c.queue = nil
	}
	return batch, c.closed
}

func (c *udpPacketConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.closed {
		c.closed = true
		close(c.closing)
		// A slow or deadline-insensitive detour must not hold the worker or
		// the leased physical connection indefinitely during shutdown.
		window := c.session.t.pool.drainWindow()
		c.closeDeadline = time.Now().Add(window)
		c.closeGuard = time.AfterFunc(window, c.session.t.abort)
	}
	return nil
}

func (c *udpPacketConn) LocalAddr() net.Addr { return c.session.LocalAddr() }

func (c *udpPacketConn) SetDeadline(t time.Time) error {
	c.mu.Lock()
	c.writeDeadline = t
	c.mu.Unlock()
	return c.session.SetDeadline(t)
}

func (c *udpPacketConn) SetWriteDeadline(t time.Time) error {
	c.mu.Lock()
	c.writeDeadline = t
	c.mu.Unlock()
	return c.session.SetWriteDeadline(t)
}

func (c *udpPacketConn) SetReadDeadline(t time.Time) error {
	return c.session.SetReadDeadline(t)
}
