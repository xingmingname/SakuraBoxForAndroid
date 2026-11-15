// Adapted from n-yarvex/nakis at 29309496e9d6a780d9080e3f6f867a7831f08f7a.
// Copyright (c) 2026 nyarvex. Licensed under MIT; see LICENSE.

package nakis

import (
	"math"
	"math/rand/v2"
	"time"
)

const (
	personaFill  = 2 * time.Millisecond
	minLifeBytes = 8 << 20
	maxLifeBytes = 256 << 20
)

func logUniform(lo, hi float64) int {
	return int(math.Exp(math.Log(lo) + rand.Float64()*(math.Log(hi)-math.Log(lo))))
}

func tlsLike(port uint16) bool {
	switch port {
	case 443, 465, 563, 636, 853, 993, 995, 5223, 8443:
		return true
	}
	return false
}

func personaPlan(up, inner bool, need int) []Range {
	count := 2 + rand.IntN(3)
	lo, hi := 60.0, 1400.0
	if up {
		count++
		lo, hi = 40, 1200
	}
	big := 5
	if inner {
		count += 2
		big = 3
	}
	count = min(count, ShapeSlots)
	plan := make([]Range, 0, count)
	for i := 0; i < count; i++ {
		l, h := lo, hi
		switch {
		case i == 0 && up:
			l, h = 110, 900
		case i == 0:
			l, h = 100, 1500
		case rand.IntN(big) == 0:
			l, h = 1400, 4000
		}
		n := min(max(logUniform(l, h), 24), ShapeCap)
		if i == 0 && up {
			n = max(n, need)
		}
		plan = append(plan, Range{n, n})
	}
	return plan
}

type lifespan struct {
	bytes int64
	uses  int
}

func newLife(maxUses int) lifespan {
	l := lifespan{bytes: int64(logUniform(minLifeBytes, maxLifeBytes))}
	if maxUses > 0 {
		low := (maxUses + 1) / 2
		l.uses = low + rand.IntN(maxUses-low+1)
	}
	return l
}

func (l lifespan) spent(uses int, moved int64) bool {
	return l.bytes > 0 && (moved >= l.bytes || l.uses > 0 && uses >= l.uses)
}
