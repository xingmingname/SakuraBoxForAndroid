package sudoku

import (
	"errors"
	"math"
	"testing"
)

func TestPhysicalTablePolicyAllChoicesAndOverflow(t *testing.T) {
	for _, limit := range []uint64{32, 64, 128, 256, 512, 0} {
		b, err := BudgetForLimit(&limit)
		if err != nil || b.PhysicalTables != limit || b.BuildBytes != limit<<20 {
			t.Fatal(limit, b, err)
		}
		for _, mode := range []string{"prefer_ascii", "up_ascii_down_entropy"} {
			directions := 1
			if mode != "prefer_ascii" {
				directions = 2
			}
			boundary := int(limit) / directions
			if limit == 0 {
				boundary = 4000
			}
			if n, err := CheckTableCount(mode, boundary, b); err != nil || n != uint64(boundary*directions) {
				t.Fatal(limit, mode, n, err)
			}
			_, err := CheckTableCount(mode, boundary+1, b)
			if errors.Is(err, ErrTableResourceLimit) != (limit != 0) {
				t.Fatal(limit, mode, err)
			}
		}
		var total TableResources
		if err := total.Add(TableResources{limit, b.BuildBytes}, b); err != nil {
			t.Fatal(err)
		}
		if limit != 0 && !errors.Is(total.Add(TableResources{0, 1}, b), ErrTableResourceLimit) {
			t.Fatal("byte quota not scaled", limit)
		}
	}
	unlimited := TableBudget{}
	for _, mode := range []string{"prefer_ascii", "up_ascii_down_entropy"} {
		if _, err := CheckTableCount(mode, math.MaxInt, unlimited); !errors.Is(err, ErrTableResourceLimit) {
			t.Fatal("addressable array overflow accepted", err)
		}
	}
	for _, resources := range []TableResources{{math.MaxUint64, 0}, {0, math.MaxUint64}} {
		before := resources
		if !errors.Is(resources.Add(TableResources{1, 1}, unlimited), ErrTableResourceLimit) || resources != before {
			t.Fatal("unlimited disabled overflow handling", resources)
		}
	}
	if _, err := tableBuildEstimate(1, math.MaxUint64); !errors.Is(err, ErrTableResourceLimit) {
		t.Fatal("input-copy overflow accepted", err)
	}
	if _, err := checkedMultiply(math.MaxUint64, 2); !errors.Is(err, ErrTableResourceLimit) {
		t.Fatal("multiply overflow", err)
	}
}

func TestUnlimitedPreflightDoesNotBuildHistorical4000Tables(t *testing.T) {
	patterns := make([]string, 4000)
	for i := range patterns {
		patterns[i] = "xpxvvpvv"
	}
	usage, err := EstimateTableResources("table-policy-synthetic-key", "up_ascii_down_entropy", patterns, TableBudget{})
	if err != nil || usage.PhysicalTables != 8000 || usage.EstimatedBytes <= 512<<20 {
		t.Fatal(usage, err)
	}
	t.Logf("unlimited preflight only: physical=%d estimate=%d; no puzzle arrays built", usage.PhysicalTables, usage.EstimatedBytes)
}
