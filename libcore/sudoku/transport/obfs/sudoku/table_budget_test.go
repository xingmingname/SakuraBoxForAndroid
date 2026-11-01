package sudoku

import (
	"errors"
	"fmt"
	"math"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestTableResourceCountBoundaries(t *testing.T) {
	for _, row := range []struct {
		mode     string
		count    int
		physical uint64
		limited  bool
	}{
		{"prefer_ascii", 0, 1, false}, {"prefer_entropy", 1, 1, false},
		{"prefer_ascii", 32, 32, false}, {"prefer_ascii", 33, 0, true},
		{"up_ascii_down_entropy", 16, 32, false}, {"up_ascii_down_entropy", 17, 0, true},
		{"up_entropy_down_ascii", 16, 32, false}, {"up_entropy_down_entropy", 32, 32, false},
		{"prefer_ascii", 4000, 0, true}, {"prefer_ascii", math.MaxInt, 0, true}, {"prefer_ascii", -1, 0, true},
	} {
		t.Run(fmt.Sprintf("%s/%d", row.mode, row.count), func(t *testing.T) {
			n, err := CheckTableCount(row.mode, row.count)
			if n != row.physical || errors.Is(err, ErrTableResourceLimit) != row.limited {
				t.Fatalf("count=%d err=%v", n, err)
			}
		})
	}
}

func TestTableResourceArithmeticAndMemoryBoundary(t *testing.T) {
	r := TableResources{}
	if err := r.Add(TableResources{MaxPhysicalTables, MaxTableBuildBytes}); err != nil {
		t.Fatal(err)
	}
	before := r
	for _, next := range []TableResources{{1, 0}, {0, 1}, {math.MaxUint64, math.MaxUint64}} {
		if !errors.Is(r.Add(next), ErrTableResourceLimit) || r != before {
			t.Fatal("overflow or mutation", r)
		}
	}
	if _, err := EstimateTableResources(strings.Repeat("k", 1<<20), "prefer_ascii", make([]string, 16)); !errors.Is(err, ErrTableResourceLimit) {
		t.Fatal(err)
	}
}

func TestTableResourceBudgetPreservesOrderDuplicatesAndDirectionalModes(t *testing.T) {
	for _, mode := range []string{"prefer_ascii", "prefer_entropy", "up_ascii_down_entropy", "up_entropy_down_ascii"} {
		patterns := []string{"xpxvvpvv", "vxpvxvvp", "xpxvvpvv"}
		set, err := NewTableSet("rotation-budget-key", mode, patterns)
		if err != nil {
			t.Fatal(err)
		}
		if len(set.Tables) != len(patterns) || set.Tables[0] == set.Tables[2] {
			t.Fatal("duplicate entry removed or reused")
		}
		for i, pattern := range patterns {
			original, err := NewTableWithCustom("rotation-budget-key", mode, pattern)
			if err != nil {
				t.Fatal(err)
			}
			if set.Tables[i].hint != original.hint || len(set.Tables[i].puzzles) != len(original.puzzles) {
				t.Fatal("wire table changed")
			}
		}
	}
	for _, row := range []struct {
		mode  string
		count int
	}{{"prefer_ascii", 32}, {"up_ascii_down_entropy", 16}} {
		patterns := make([]string, row.count)
		for i := range patterns {
			patterns[i] = "xpxvvpvv"
		}
		estimate, err := EstimateTableResources("boundary-key", row.mode, patterns)
		if err != nil {
			t.Fatal(err)
		}
		runtime.GC()
		var before, after runtime.MemStats
		runtime.ReadMemStats(&before)
		set, err := NewTableSet("boundary-key", row.mode, patterns)
		if err != nil {
			t.Fatal(err)
		}
		runtime.ReadMemStats(&after)
		runtime.KeepAlive(set)
		if after.TotalAlloc-before.TotalAlloc > estimate.EstimatedBytes {
			t.Fatalf("estimate %d < allocation %d", estimate.EstimatedBytes, after.TotalAlloc-before.TotalAlloc)
		}
		t.Logf("%s entries=%d physical=%d estimate=%d allocation=%d", row.mode, row.count, estimate.PhysicalTables, estimate.EstimatedBytes, after.TotalAlloc-before.TotalAlloc)
	}
}

func TestOversizedTableSetFailsBeforeAllocation(t *testing.T) {
	patterns := make([]string, 4000)
	for i := range patterns {
		patterns[i] = "xpxvvpvv"
	}
	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)
	set, err := NewTableSet("secret-not-in-errors", "up_ascii_down_entropy", patterns)
	runtime.ReadMemStats(&after)
	var typed *TableResourceLimitError
	if set != nil || !errors.Is(err, ErrTableResourceLimit) || !errors.As(err, &typed) {
		t.Fatal(set, err)
	}
	if strings.Contains(err.Error(), "secret-not-in-errors") {
		t.Fatal("secret in error")
	}
	if after.TotalAlloc-before.TotalAlloc > 64<<10 {
		t.Fatal("large allocation before rejection", after.TotalAlloc-before.TotalAlloc)
	}
	t.Logf("4000 entries rejected: allocation=%d error=%v", after.TotalAlloc-before.TotalAlloc, err)
}

func TestTableResourceExactEstimatedByteBoundary(t *testing.T) {
	const key = "boundary-key"
	patterns := make([]string, MaxPhysicalTables)
	for i := range patterns {
		patterns[i] = "xpxvvpvv"
	}
	usage, err := EstimateTableResources(key, "prefer_entropy", patterns)
	if err != nil {
		t.Fatal(err)
	}
	// Whitespace is accepted by the unchanged layout parser. Its raw length
	// still consumes the conservative input-copy allowance; no data is removed.
	unit := uint64(4 * MaxPhysicalTables)
	remaining := MaxTableBuildBytes - usage.EstimatedBytes
	if remaining%unit != 0 {
		t.Fatal("boundary fixture no longer reaches an exact byte estimate")
	}
	patterns[0] = strings.Repeat(" ", int(remaining/unit)) + patterns[0]
	usage, err = EstimateTableResources(key, "prefer_entropy", patterns)
	if err != nil || usage.EstimatedBytes != MaxTableBuildBytes {
		t.Fatal("exact byte boundary", usage, err)
	}
	set, err := NewTableSet(key, "prefer_entropy", patterns)
	if err != nil || len(set.Tables) != MaxPhysicalTables {
		t.Fatal("exact byte boundary construction", err)
	}
	patterns[0] = " " + patterns[0]
	if _, err = NewTableSet(key, "prefer_entropy", patterns); !errors.Is(err, ErrTableResourceLimit) {
		t.Fatal("byte budget exceeded without rejection", err)
	}
	t.Logf("exact estimated bytes=%d constructed; one additional input byte rejected", usage.EstimatedBytes)
	runtime.KeepAlive(set)
}

// Only small inputs use the old algorithm, for an allocation comparison. Never
// call this test-only baseline with the historical 4000-entry configuration.
func TestF03BoundedBeforeAfterResourceProbe(t *testing.T) {
	for _, count := range []int{1, 4, 16} {
		patterns := make([]string, count)
		for i := range patterns {
			patterns[i] = "xpxvvpvv"
		}
		for _, guarded := range []bool{false, true} {
			runtime.GC()
			var before, after runtime.MemStats
			runtime.ReadMemStats(&before)
			start := time.Now()
			var set *TableSet
			if guarded {
				var err error
				set, err = NewTableSet("audit-synthetic-key", "up_ascii_down_entropy", patterns)
				if err != nil {
					t.Fatal(err)
				}
			} else {
				set = &TableSet{Tables: make([]*Table, 0, count)}
				for _, pattern := range patterns {
					table, err := NewTableWithCustom("audit-synthetic-key", "up_ascii_down_entropy", pattern)
					if err != nil {
						t.Fatal(err)
					}
					set.Tables = append(set.Tables, table)
				}
			}
			elapsed := time.Since(start)
			runtime.GC()
			runtime.ReadMemStats(&after)
			t.Logf("guarded=%t entries=%d retained_heap_delta=%d total_alloc_delta=%d elapsed=%s", guarded, count, int64(after.HeapAlloc)-int64(before.HeapAlloc), after.TotalAlloc-before.TotalAlloc, elapsed)
			runtime.KeepAlive(set)
		}
	}
}
