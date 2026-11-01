package sudoku

import (
	"errors"
	"fmt"
	"strconv"
)

// Defaults for callers without a node policy. Zero quotas mean unlimited;
// checked arithmetic and addressable-allocation checks always apply.
const MaxPhysicalTables = 32
const MaxTableBuildBytes uint64 = 32 << 20

type TableBudget struct {
	PhysicalTables uint64
	BuildBytes     uint64
}

func BudgetForLimit(limit *uint64) (TableBudget, error) {
	n := uint64(MaxPhysicalTables)
	if limit != nil {
		n = *limit
	}
	switch n {
	case 0, 32, 64, 128, 256, 512:
		return TableBudget{n, n << 20}, nil
	default:
		return TableBudget{}, &TableResourceLimitError{fmt.Sprintf("unsupported physical table limit %d", n)}
	}
}
func selectedBudget(budgets []TableBudget) TableBudget {
	if len(budgets) != 0 {
		return budgets[0]
	}
	return TableBudget{MaxPhysicalTables, MaxTableBuildBytes}
}

var ErrTableResourceLimit = errors.New("SUDOKU_TABLE_RESOURCE_LIMIT")

type TableResourceLimitError struct{ Reason string }

func (e *TableResourceLimitError) Error() string {
	return fmt.Sprintf("%s: %s", ErrTableResourceLimit, e.Reason)
}
func (e *TableResourceLimitError) Unwrap() error { return ErrTableResourceLimit }

type TableResources struct {
	PhysicalTables uint64
	EstimatedBytes uint64
}

func checkedAdd(a, b uint64) (uint64, error) {
	if b > ^uint64(0)-a {
		return 0, &TableResourceLimitError{"resource accounting overflow"}
	}
	return a + b, nil
}
func checkedMultiply(a, b uint64) (uint64, error) {
	if a != 0 && b > ^uint64(0)/a {
		return 0, &TableResourceLimitError{"resource accounting overflow"}
	}
	return a * b, nil
}

// Whole-config statistics pass an unlimited budget: each outbound has already
// passed its OWN budget. Check all arithmetic before mutating the totals.
func (r *TableResources) Add(next TableResources, budgets ...TableBudget) error {
	b := selectedBudget(budgets)
	count, err := checkedAdd(r.PhysicalTables, next.PhysicalTables)
	if err != nil {
		return err
	}
	bytes, err := checkedAdd(r.EstimatedBytes, next.EstimatedBytes)
	if err != nil {
		return err
	}
	if b.PhysicalTables != 0 && count > b.PhysicalTables {
		return &TableResourceLimitError{fmt.Sprintf("physical table count exceeds limit %d", b.PhysicalTables)}
	}
	if b.BuildBytes != 0 && bytes > b.BuildBytes {
		return &TableResourceLimitError{fmt.Sprintf("estimated table construction memory exceeds %d bytes", b.BuildBytes)}
	}
	r.PhysicalTables, r.EstimatedBytes = count, bytes
	return nil
}

// Reject oversized finite lists before traversing entries or hashing a key.
func CheckTableCount(mode string, entries int, budgets ...TableBudget) (uint64, error) {
	b := selectedBudget(budgets)
	if entries < 0 {
		return 0, &TableResourceLimitError{"negative table entry count"}
	}
	if b.PhysicalTables != 0 && uint64(entries) > b.PhysicalTables {
		return 0, &TableResourceLimitError{fmt.Sprintf("table entry count exceeds limit %d", b.PhysicalTables)}
	}
	parsed, err := ParseASCIIMode(mode)
	if err != nil {
		return 0, err
	}
	if entries == 0 {
		entries = 1
	}
	directions := uint64(1)
	if parsed.Uplink != parsed.Downlink {
		directions = 2
	}
	maxInt := uint64(^uint(0) >> 1)
	if uint64(entries) > maxInt/directions || uint64(entries) > maxInt/uint64(strconv.IntSize/8) {
		return 0, &TableResourceLimitError{"table count or pointer-array allocation size overflows platform int"}
	}
	physical := uint64(entries) * directions
	if b.PhysicalTables != 0 && physical > b.PhysicalTables {
		return 0, &TableResourceLimitError{fmt.Sprintf("directional table count exceeds limit %d", b.PhysicalTables)}
	}
	return physical, nil
}

// Puzzles depend on the key-seeded grids, not byte layouts. This bounded
// workspace never builds puzzle arrays, including when quotas are disabled.
func EstimateTableResources(key, mode string, patterns []string, budgets ...TableBudget) (TableResources, error) {
	b := selectedBudget(budgets)
	physical, err := CheckTableCount(mode, len(patterns), b)
	if err != nil {
		return TableResources{}, err
	}
	inputBytes, err := tableInputBytes(key, patterns, physical, b)
	if err != nil {
		return TableResources{}, err
	}
	var scratch Table
	grids := shuffledGrids(key)
	for k, g := range grids {
		for pos, val := range g {
			scratch.hintSets[pos<<2|int(val-1)][k>>6] |= 1 << (k & 63)
		}
	}
	puzzles := uint64(0)
	for byteVal := 0; byteVal < 256; byteVal++ {
		g := &grids[byteVal]
		for _, positions := range hintPositions {
			if got, ok := scratch.decodeHints(positions[0]<<2|(g[positions[0]]-1), positions[1]<<2|(g[positions[1]]-1), positions[2]<<2|(g[positions[2]]-1), positions[3]<<2|(g[positions[3]]-1)); ok && got == byte(byteVal) {
				puzzles++
			}
		}
	}
	perTable, err := tableBuildEstimate(puzzles, inputBytes)
	if err != nil {
		return TableResources{}, err
	}
	bytes, err := checkedMultiply(perTable, physical)
	if err != nil {
		return TableResources{}, err
	}
	usage := TableResources{physical, bytes}
	var total TableResources
	if err = total.Add(usage, b); err != nil {
		return TableResources{}, err
	}
	return usage, nil
}
func CheckTableInputs(key string, patterns []string, physical uint64, budget TableBudget) error {
	_, err := tableInputBytes(key, patterns, physical, budget)
	return err
}
func tableInputBytes(key string, patterns []string, physical uint64, budget TableBudget) (uint64, error) {
	inputBytes := uint64(len(key))
	for _, pattern := range patterns {
		var err error
		inputBytes, err = checkedAdd(inputBytes, uint64(len(pattern)))
		if err != nil {
			return 0, err
		}
	}
	if inputBytes > uint64(^uint(0)>>1)-1024 {
		return 0, &TableResourceLimitError{"table input string allocation size overflows platform int"}
	}
	copies, err := checkedMultiply(4, physical)
	if err != nil {
		return 0, err
	}
	bytes, err := checkedMultiply(inputBytes, copies)
	if err != nil {
		return 0, err
	}
	if budget.BuildBytes != 0 && bytes > budget.BuildBytes {
		return 0, &TableResourceLimitError{fmt.Sprintf("table input copies exceed %d bytes", budget.BuildBytes)}
	}
	return inputBytes, nil
}
func tableBuildEstimate(puzzles, inputBytes uint64) (uint64, error) {
	if puzzles < 24<<10 {
		puzzles = 24 << 10
	}
	// Pinned Go 1.26.9 envelope: 8x all puzzle backing allocations, 64 KiB
	// auxiliary/workspace and 4x selected input-copy allowance per table.
	arrays, err := checkedMultiply(puzzles, 4*8)
	if err != nil {
		return 0, err
	}
	copies, err := checkedMultiply(inputBytes, 4)
	if err != nil {
		return 0, err
	}
	base, err := checkedAdd(arrays, 64<<10)
	if err != nil {
		return 0, err
	}
	return checkedAdd(base, copies)
}
func CheckTableResources(key, mode string, patterns []string, budgets ...TableBudget) error {
	b := selectedBudget(budgets)
	physical, err := CheckTableCount(mode, len(patterns), b)
	if err != nil {
		return err
	}
	inputBytes, err := tableInputBytes(key, patterns, physical, b)
	if err != nil {
		return err
	}
	if b.BuildBytes != 0 {
		upper, e := tableBuildEstimate(256*hintPositionsCount, inputBytes)
		if e == nil {
			if bytes, e := checkedMultiply(upper, physical); e == nil && bytes <= b.BuildBytes {
				return nil
			}
		}
	}
	_, err = EstimateTableResources(key, mode, patterns, b)
	return err
}
