// Adapted from n-yarvex/nakis at 01be745ee68c737a685999d0cc7cb47c9fb048ea.
// Copyright (c) 2026 nyarvex. Licensed under MIT; see LICENSE.

package nakis

import (
	"bufio"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math/bits"
	"net"
	"os"
	"sync"
	"sync/atomic"
)

const BufMax = 1 << 16

var tiers [17]sync.Pool

func init() {
	for i := range tiers {
		size := 1 << i
		tiers[i].New = func() any { return make([]byte, size) }
	}
}

func GetBuf(n int) []byte {
	if n <= 0 {
		return nil
	}
	if n > BufMax {
		return make([]byte, n)
	}
	return tiers[bits.Len(uint(n-1))].Get().([]byte)[:n]
}

func PutBuf(b []byte) {
	c := cap(b)
	if c == 0 || c > BufMax || c&(c-1) != 0 {
		return
	}
	tiers[bits.Len(uint(c))-1].Put(b[:c])
}

const (
	Headroom = 2
	FrameMax = 0xFFFE
	padMark  = 0xFFFF

	HelloVersion byte = 1

	CmdHug byte = 1

	RespCozy byte = 0
	RespOops byte = 2
)

var (
	ErrNap      = errors.New("nakis: nap")
	ErrOversize = errors.New("nakis: frame too large")
	CozyByte    = []byte{RespCozy}
)

type MewError struct {
	Code byte
	Msg  string
}

func (e *MewError) Error() string {
	return fmt.Sprintf("nakis peer code %d: %q", e.Code, e.Msg)
}

func HelloBytes(host string, port uint16) ([]byte, error) {
	if len(host) == 0 || len(host) > 255 {
		return nil, errors.New("nakis: bad host length")
	}
	cmd := CmdHug
	buf := make([]byte, 0, 5+len(host)+2)
	buf = append(buf, HelloVersion, cmd, 0, byte(len(host)))
	buf = append(buf, host...)
	return binary.BigEndian.AppendUint16(buf, port), nil
}

type Batcher struct {
	net.Conn
	mu  sync.Mutex
	on  bool
	buf []byte

	// TLS may read ahead beyond a NAP. Track raw record boundaries without
	// changing how much the underlying transport reads per syscall.
	header    [5]byte
	headerLen int
	remaining int
}

func NewBatcher(c net.Conn) *Batcher {
	return &Batcher{Conn: c}
}

func (b *Batcher) Read(p []byte) (int, error) {
	n, err := b.Conn.Read(p)
	for data := p[:n]; len(data) != 0; {
		if b.headerLen < len(b.header) {
			copied := copy(b.header[b.headerLen:], data)
			b.headerLen += copied
			data = data[copied:]
			if b.headerLen < len(b.header) {
				break
			}
			b.remaining = int(binary.BigEndian.Uint16(b.header[3:]))
		}
		used := min(len(data), b.remaining)
		data = data[used:]
		b.remaining -= used
		if b.remaining == 0 {
			b.headerLen = 0
		}
	}
	return n, err
}

func (b *Batcher) begin(hint int) {
	b.mu.Lock()
	b.on = true
	if b.buf == nil {
		b.buf = GetBuf(hint)[:0]
	}
	b.mu.Unlock()
}

func (b *Batcher) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !b.on {
		return b.Conn.Write(p)
	}
	b.buf = append(b.buf, p...)
	return len(p), nil
}

func (b *Batcher) flush() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.on = false
	if len(b.buf) == 0 {
		return nil
	}
	_, err := b.Conn.Write(b.buf)
	PutBuf(b.buf)
	b.buf = nil
	return err
}

func recordHint(n int) int {
	return n + (n/16000+1)*24
}

type Tunnel struct {
	net.Conn
	rd    io.Reader
	batch *Batcher

	rMu  sync.Mutex
	held []byte
	rest []byte

	wMu sync.Mutex

	moved  atomic.Int64
	bind   []byte
	sealed bool
}

func WrapTunnel(c net.Conn, b *Batcher) *Tunnel {
	m := &Tunnel{Conn: c, batch: b, rd: c}
	if b == nil {
		m.rd = bufio.NewReaderSize(c, 16<<10)
	}
	return m
}

var errPartialFrame = errors.New("nakis: incomplete tunnel frame")

func readDeadline(err error) bool {
	var timeout net.Error
	return !errors.Is(err, errPartialFrame) && errors.As(err, &timeout) && timeout.Timeout() && timeout.Temporary()
}

func (c *Tunnel) pull(reader io.Reader) ([]byte, error) {
	for {
		var h [2]byte
		if n, err := io.ReadFull(reader, h[:]); err != nil {
			if n != 0 {
				err = fmt.Errorf("%w: %w", errPartialFrame, err)
			}
			return nil, err
		}
		n := int(binary.BigEndian.Uint16(h[:]))
		switch n {
		case 0:
			c.noteMoved(2)
			return nil, ErrNap
		case padMark:
			if _, err := io.ReadFull(reader, h[:]); err != nil {
				return nil, fmt.Errorf("%w: %w", errPartialFrame, err)
			}
			skip := int64(binary.BigEndian.Uint16(h[:]))
			if _, err := io.CopyN(io.Discard, reader, skip); err != nil {
				return nil, fmt.Errorf("%w: %w", errPartialFrame, err)
			}
			c.noteMoved(4 + skip)
			continue
		}
		p := GetBuf(n)
		if _, err := io.ReadFull(reader, p); err != nil {
			PutBuf(p)
			return nil, fmt.Errorf("%w: %w", errPartialFrame, err)
		}
		c.noteMoved(int64(2 + n))
		return p, nil
	}
}

func (c *Tunnel) Moved() int64 { return c.moved.Load() }

// No retirement decision needs a count above the largest possible lifespan.
func (c *Tunnel) noteMoved(n int64) {
	for old := c.moved.Load(); old < maxLifeBytes; old = c.moved.Load() {
		if c.moved.CompareAndSwap(old, min(old+n, maxLifeBytes)) {
			return
		}
	}
}

func (c *Tunnel) drain(replied bool) error {
	c.rMu.Lock()
	defer c.rMu.Unlock()
	if c.held != nil {
		PutBuf(c.held)
		c.held, c.rest = nil, nil
	}
	for {
		frame, err := c.pull(c.rd)
		if err != nil {
			if errors.Is(err, ErrNap) && replied {
				return nil
			}
			return err
		}
		ok := replied || frame[0] == RespCozy
		PutBuf(frame)
		if !ok {
			return errors.New("nakis: unexpected response while draining")
		}
		replied = true
	}
}

func (c *Tunnel) pendingRead() bool {
	if len(c.rest) != 0 || c.batch != nil && c.batch.headerLen != 0 {
		return true
	}
	buffered, ok := c.rd.(*bufio.Reader)
	return ok && buffered.Buffered() != 0
}

func (c *Tunnel) idleClean(watching chan<- struct{}) bool {
	c.rMu.Lock()
	defer c.rMu.Unlock()
	close(watching)
	if c.pendingRead() {
		return false
	}
	// Read at the Nakis layer so decrypted bytes in tls.Conn are visible.
	// A timeout only certifies an empty reader at a complete record boundary.
	var probe [1]byte
	_, err := io.ReadFull(c.rd, probe[:])
	// Go TLS only preserves its read state on a temporary transport error.
	timeout, ok := err.(net.Error)
	return ok && timeout.Temporary() && errors.Is(err, os.ErrDeadlineExceeded) && !c.pendingRead()
}

func (c *Tunnel) Read(b []byte) (int, error) {
	if len(b) == 0 {
		return 0, nil
	}
	c.rMu.Lock()
	defer c.rMu.Unlock()
	if len(c.rest) == 0 {
		p, err := c.pull(c.rd)
		if err != nil {
			return 0, err
		}
		c.held, c.rest = p, p
	}
	n := copy(b, c.rest)
	c.rest = c.rest[n:]
	if len(c.rest) == 0 {
		PutBuf(c.held)
		c.held, c.rest = nil, nil
	}
	return n, nil
}

func (c *Tunnel) ReadByte() (byte, error) {
	var b [1]byte
	_, err := io.ReadFull(c, b[:])
	return b[0], err
}

const recordPlain = 16384

func (c *Tunnel) put(p []byte) error {
	c.noteMoved(int64(len(p)))
	if c.batch == nil || len(p) <= recordPlain {
		_, err := c.Conn.Write(p)
		return err
	}
	c.batch.begin(recordHint(len(p)))
	_, err := c.Conn.Write(p)
	if ferr := c.batch.flush(); err == nil {
		err = ferr
	}
	return err
}

func (c *Tunnel) SendWire(wire []byte) error {
	n := len(wire) - Headroom
	if n <= 0 {
		return nil
	}
	if n > FrameMax {
		return ErrOversize
	}
	binary.BigEndian.PutUint16(wire, uint16(n))
	c.wMu.Lock()
	defer c.wMu.Unlock()
	return c.put(wire)
}

func (c *Tunnel) SendWirePad(wire []byte, pad int) error {
	n := len(wire) - Headroom
	if pad < 4 || n <= 0 || n > FrameMax || len(wire)+pad > BufMax {
		return c.SendWire(wire)
	}
	out := GetBuf(len(wire) + pad)
	defer PutBuf(out)
	copy(out, wire)
	binary.BigEndian.PutUint16(out, uint16(n))
	tail := out[len(wire):]
	binary.BigEndian.PutUint16(tail, padMark)
	binary.BigEndian.PutUint16(tail[2:], uint16(pad-4))
	_, _ = rand.Read(tail[4:])
	c.wMu.Lock()
	defer c.wMu.Unlock()
	return c.put(out)
}

func (c *Tunnel) Send(head, body []byte) error {
	total := len(head) + len(body)
	for off := 0; off < total; {
		n := min(total-off, FrameMax)
		wire := GetBuf(Headroom + n)
		fill := copyRange(wire[Headroom:], head, body, off)
		if fill != n {
			PutBuf(wire)
			return io.ErrShortWrite
		}
		err := c.SendWire(wire)
		PutBuf(wire)
		if err != nil {
			return err
		}
		off += n
	}
	return nil
}

func copyRange(dst, head, body []byte, off int) int {
	n := 0
	if off < len(head) {
		n = copy(dst, head[off:])
		off = len(head)
	}
	return n + copy(dst[n:], body[off-len(head):])
}

func (c *Tunnel) Write(b []byte) (int, error) {
	if err := c.Send(nil, b); err != nil {
		return 0, err
	}
	return len(b), nil
}

func (c *Tunnel) Nap() error {
	c.wMu.Lock()
	defer c.wMu.Unlock()
	var zero [2]byte
	return c.put(zero[:])
}

func (c *Tunnel) ReadReply() error {
	code, err := c.ReadByte()
	if err != nil {
		return err
	}
	switch code {
	case RespCozy:
		return nil
	case RespOops:
	default:
		return fmt.Errorf("nakis: unknown response code 0x%x", code)
	}
	var h [2]byte
	if _, err := io.ReadFull(c, h[:]); err != nil {
		return err
	}
	msg := make([]byte, h[1])
	if _, err := io.ReadFull(c, msg); err != nil {
		return err
	}
	return &MewError{Code: h[0], Msg: string(msg)}
}
