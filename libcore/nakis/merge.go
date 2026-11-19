// Adapted from n-yarvex/nakis at 01be745ee68c737a685999d0cc7cb47c9fb048ea.
// Copyright (c) 2026 nyarvex. Licensed under MIT; see LICENSE.

package nakis

import (
	"sync"
	"time"
)

type Box struct {
	head    int
	limit   int
	items   [][]byte
	first   int
	size    int
	scratch []byte
}

func NewBox(limit, head int) *Box {
	return &Box{limit: limit, head: head}
}

func (b *Box) Empty() bool { return b.first >= len(b.items) }

func (b *Box) Size() int { return b.size }

func (b *Box) compact() {
	if b.first > 32 && b.first*2 >= len(b.items) {
		n := copy(b.items, b.items[b.first:])
		clear(b.items[n:])
		b.items = b.items[:n]
		b.first = 0
	}
}

func (b *Box) Sprinkle(buf []byte) {
	n := len(buf) - b.head
	if n <= 0 {
		return
	}
	b.items = append(b.items, buf)
	b.size += n
}

func (b *Box) nibble() []byte {
	if b.Empty() {
		return nil
	}
	d := b.items[b.first]
	b.items[b.first] = nil
	b.first++
	b.size -= len(d) - b.head
	b.compact()
	return d
}

func (b *Box) Bundle(lead []byte) (out []byte, merged bool) {
	if lead == nil {
		if b.Empty() {
			return nil, false
		}
		lead = b.nibble()
	}
	if b.Empty() {
		return lead, false
	}
	total := len(lead) - b.head
	end := b.first
	for end < len(b.items) {
		x := len(b.items[end]) - b.head
		if total+x > b.limit {
			break
		}
		total += x
		end++
	}
	if end == b.first {
		return lead, false
	}
	if b.scratch == nil {
		b.scratch = GetBuf(b.head + b.limit)
	}
	out = b.scratch
	o := b.head + copy(out[b.head:], lead[b.head:])
	PutBuf(lead)
	for b.first < end {
		x := b.items[b.first]
		b.items[b.first] = nil
		b.first++
		b.size -= len(x) - b.head
		o += copy(out[o:], x[b.head:])
		PutBuf(x)
	}
	b.compact()
	return out[:o], true
}

func (b *Box) Drain() {
	for i := b.first; i < len(b.items); i++ {
		PutBuf(b.items[i])
	}
	b.items = nil
	b.first = 0
	b.size = 0
}

func (b *Box) Dispose() {
	b.Drain()
	PutBuf(b.scratch)
	b.scratch = nil
}

const simmerTick = 250 * time.Microsecond

const lullGap = 40 * time.Microsecond

type Range struct{ Min, Max int }

type GateOptions struct {
	Pack  int
	Tail  int
	Quiet int
	Head  int
	Plan  []Range
	Fill  time.Duration
	Pad   bool
}

type Gate struct {
	mu   sync.Mutex
	box  *Box
	opt  GateOptions
	last time.Time
	emit func(wire []byte, pad int) error
	dead bool

	timer *time.Timer
	epoch uint64
	gen   int64
	seen  int64
	round int

	idx       int
	acc       []byte
	fillTimer *time.Timer
	fillEpoch uint64
}

func NewGate(o GateOptions, emit func(wire []byte, pad int) error) *Gate {
	return &Gate{
		box:  NewBox(o.Pack, o.Head),
		opt:  o,
		emit: emit,
	}
}

func (g *Gate) full() bool {
	return g.box.size >= g.opt.Pack || g.opt.Pack-g.box.size < g.opt.Tail
}

func (g *Gate) disarm() {
	if g.timer != nil {
		g.timer.Stop()
		g.timer = nil
	}
	g.epoch++
}

func (g *Gate) scoop() {
	g.disarm()
	g.round = 0
	for !g.box.Empty() {
		out, merged := g.box.Bundle(nil)
		if out == nil {
			break
		}
		err := g.emit(out, 0)
		if !merged {
			PutBuf(out)
		}
		if err != nil {
			g.dead = true
			return
		}
	}
}

func (g *Gate) simmer() {
	if g.box.Empty() || g.timer != nil {
		return
	}
	if g.full() {
		g.scoop()
		return
	}
	g.epoch++
	id := g.epoch
	g.seen = g.gen
	g.timer = time.AfterFunc(simmerTick, func() { g.onSimmer(id) })
}

func (g *Gate) onSimmer(id uint64) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.dead || id != g.epoch {
		return
	}
	g.timer = nil
	if g.box.Empty() {
		return
	}
	if g.full() {
		g.scoop()
		return
	}
	if g.round < g.opt.Quiet*int(time.Millisecond/simmerTick) && g.gen != g.seen {
		g.round++
		g.seen = g.gen
		g.simmer()
		return
	}
	g.scoop()
}

func (g *Gate) feedLocked(src []byte) {
	head := g.opt.Head
	for off := 0; off < len(src); {
		now := time.Now()
		lull := g.box.Empty() && now.Sub(g.last) >= lullGap
		g.last = now
		room := min(g.opt.Pack-g.box.size, len(src)-off)
		if room <= 0 {
			g.scoop()
			if g.dead {
				return
			}
			continue
		}
		piece := GetBuf(head + room)
		copy(piece[head:], src[off:off+room])
		g.box.Sprinkle(piece)
		g.gen++
		off += room
		if lull || g.full() {
			g.scoop()
			if g.dead {
				return
			}
		} else {
			g.simmer()
		}
	}
}

func (g *Gate) shaping() bool { return g.idx < len(g.opt.Plan) }

func (g *Gate) emitShaped(b []byte, pad int) {
	w := GetBuf(g.opt.Head + len(b))
	copy(w[g.opt.Head:], b)
	if g.emit(w, pad) != nil {
		g.dead = true
	}
	PutBuf(w)
}

func (g *Gate) fillDisarm() {
	if g.fillTimer != nil {
		g.fillTimer.Stop()
		g.fillTimer = nil
	}
	g.fillEpoch++
}

func (g *Gate) fillArm() {
	if g.fillTimer != nil {
		return
	}
	g.fillEpoch++
	id := g.fillEpoch
	g.fillTimer = time.AfterFunc(g.opt.Fill, func() { g.onFill(id) })
}

func (g *Gate) onFill(id uint64) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.dead || id != g.fillEpoch {
		return
	}
	g.fillTimer = nil
	g.shapeDrain(true)
}

func (g *Gate) shapeDrain(force bool) {
	emitted := false
	for g.shaping() && len(g.acc) > 0 {
		r := g.opt.Plan[g.idx]
		if len(g.acc) < r.Min && !force {
			break
		}
		n := min(len(g.acc), r.Max)
		pad := 0
		if g.opt.Pad && n < r.Min && r.Min-n >= 4 {
			pad = r.Min - n
		}
		g.emitShaped(g.acc[:n], pad)
		if g.dead {
			return
		}
		g.acc = g.acc[:copy(g.acc, g.acc[n:])]
		g.idx++
		emitted = true
	}
	if emitted || !g.shaping() || len(g.acc) == 0 {
		g.fillDisarm()
	}
	if g.shaping() {
		if len(g.acc) > 0 {
			g.fillArm()
		}
		return
	}
	rest := g.acc
	g.acc = nil
	if len(rest) > 0 {
		g.feedLocked(rest)
	}
}

func (g *Gate) Feed(src []byte) {
	if len(src) == 0 {
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.dead {
		return
	}
	if g.shaping() {
		g.acc = append(g.acc, src...)
		g.shapeDrain(false)
		return
	}
	g.feedLocked(src)
}

func (g *Gate) Flush() {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.dead {
		return
	}
	if g.shaping() {
		g.shapeDrain(true)
		if g.dead {
			return
		}
	}
	g.scoop()
}

func (g *Gate) Close() {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.dead = true
	g.disarm()
	g.fillDisarm()
	g.acc = nil
	g.box.Dispose()
}
