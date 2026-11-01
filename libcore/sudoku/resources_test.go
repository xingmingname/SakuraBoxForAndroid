package sudoku

import (
	"errors"
	"reflect"
	"testing"

	sudokuo "libcore/sudoku/transport/obfs/sudoku"
)

func TestClientResourcePrecedenceAndUnchangedOptions(t *testing.T) {
	for _, options := range []OutboundOptions{
		{Key: "shared", CustomTable: " xpxvvpvv "},
		{Key: "shared", CustomTable: "ignored-invalid", CustomTables: []string{"xpxvvpvv", "xpxvvpvv"}},
		{Key: "shared", CustomTables: []string{""}, ASCII: "up_entropy_down_ascii"},
		{Key: "shared", CustomTable: "   "},
	} {
		before := options
		usage, err := EstimateResources(options)
		if err != nil {
			t.Fatal(err)
		}
		tables, err := buildProtocolTables(options.Key, options.ASCII, options.CustomTable, options.CustomTables, true)
		if err != nil {
			t.Fatal(err)
		}
		want := len(tables)
		if options.ASCII == "up_entropy_down_ascii" {
			want *= 2
		}
		if usage.PhysicalTables != uint64(want) || !reflect.DeepEqual(before, options) {
			t.Fatal("precedence or options mutated", usage)
		}
	}
	options := OutboundOptions{Key: "shared", ASCII: "up_ascii_down_entropy", CustomTable: "xpxvvpvv", CustomTables: make([]string, 17)}
	if _, err := EstimateResources(options); !errors.Is(err, sudokuo.ErrTableResourceLimit) {
		t.Fatal(err)
	}
}
