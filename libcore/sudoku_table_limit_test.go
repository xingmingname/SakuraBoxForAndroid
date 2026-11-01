package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/sagernet/sing-box/option"
	"libcore/sudoku"
	sudokuo "libcore/sudoku/transport/obfs/sudoku"
)

func TestSudokuTableLimitJVMEntryFixtures(t *testing.T) {
	base := os.Getenv("NEKO_CONFIG_FIXTURES")
	if base == "" {
		t.Skip("JVM fixtures not supplied")
	}
	paths, err := filepath.Glob(filepath.Join(base, "table-limits", "*.json"))
	if err != nil || len(paths) < 13 {
		t.Fatalf("table limit JVM fixtures missing: %d %v", len(paths), err)
	}
	for _, path := range paths {
		t.Run(filepath.Base(path), func(t *testing.T) {
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			var out map[string]any
			if err = json.Unmarshal(data, &out); err != nil {
				t.Fatal(err)
			}
			out["tag"] = "proxy"
			config := string(sudokuTestConfig(out))
			if err = CheckConfig(config); err != nil {
				t.Fatal(err)
			}
			instance, err := newSingBoxInstance(config, nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			if err = instance.Close(); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestSudokuTableLimitAllSixChoicesReachActualConstructor(t *testing.T) {
	for _, row := range []struct {
		limit   uint64
		entries int
	}{{32, 32}, {64, 33}, {128, 65}, {256, 129}, {512, 512}, {0, 513}} {
		t.Run(fmt.Sprint(row.limit), func(t *testing.T) {
			out := f03Out(row.entries, "prefer_entropy")
			out["table_limit"] = row.limit
			config := string(sudokuTestConfig(out))
			if err := CheckConfig(config); err != nil {
				t.Fatal(err)
			}
			instance, err := newSingBoxInstance(config, nil, nil)
			if err != nil {
				t.Fatal("selected policy did not reach table factory", err)
			}
			if err = instance.Close(); err != nil {
				t.Fatal(err)
			}
			f03ConnectAfterFailure(t, row.limit)
			t.Logf("limit=%d actual entries=%d constructed; local TCP and URL Test passed", row.limit, row.entries)
		})
		runtime.GC()
	}
}

func TestSudokuTableLimitIndependentMixedConfigAndJSONPresence(t *testing.T) {
	var opts option.Options
	for _, row := range []struct {
		limit uint64
		count int
	}{{32, 32}, {64, 64}, {0, 513}} {
		values := make([]string, row.count)
		for i := range values {
			values[i] = "xpxvvpvv"
		}
		limit := row.limit
		out := &sudoku.OutboundOptions{Key: "local-test-key", ASCII: "prefer_ascii", CustomTables: values, TableLimit: &limit}
		opts.Outbounds = append(opts.Outbounds, option.Outbound{Type: "sudoku", Tag: fmt.Sprint(row.limit), Options: out})
	}
	usage, err := estimateSudokuResources(opts)
	if err != nil || usage.PhysicalTables != 609 || usage.EstimatedBytes <= 32<<20 {
		t.Fatal("global statistics reapplied an old quota", usage, err)
	}
	limited := opts.Outbounds[0].Options.(*sudoku.OutboundOptions)
	limited.CustomTables = append(limited.CustomTables, "xpxvvpvv")
	if _, err = estimateSudokuResources(opts); !errors.Is(err, sudokuo.ErrTableResourceLimit) {
		t.Fatal("unlimited neighbor disabled another node's quota", err)
	}
	for _, raw := range []string{`{}`, `{"table_limit":32}`, `{"table_limit":64}`, `{"table_limit":0}`} {
		var out sudoku.OutboundOptions
		if err := json.Unmarshal([]byte(raw), &out); err != nil {
			t.Fatal(err)
		}
		encoded, err := json.Marshal(out)
		if err != nil {
			t.Fatal(err)
		}
		var stored map[string]any
		json.Unmarshal(encoded, &stored)
		_, present := stored["table_limit"]
		if present != (raw != `{}`) {
			t.Fatal("optional policy presence lost", string(encoded))
		}
	}
}

func TestSudokuTableLimitScaledMemoryAndUnlimitedCoreCheck(t *testing.T) {
	for _, limit := range []uint64{32, 64, 128, 256, 512} {
		out := f03Out(int(limit)+1, "prefer_ascii")
		out["table_limit"] = limit
		if err := CheckConfig(string(sudokuTestConfig(out))); !errors.Is(err, sudokuo.ErrTableResourceLimit) {
			t.Fatal(limit, err)
		}
	}
	out := f03Out(4000, "up_ascii_down_entropy")
	out["table_limit"] = 0
	// Only decode/estimate this historical large fixture; never construct it.
	if err := CheckConfig(string(sudokuTestConfig(out))); err != nil {
		t.Fatal(err)
	}
	key := make([]byte, 9<<20)
	for i := range key {
		key[i] = 'k'
	}
	options := sudoku.OutboundOptions{Key: string(key)}
	if _, err := sudoku.EstimateResources(options); !errors.Is(err, sudokuo.ErrTableResourceLimit) {
		t.Fatal("default input budget", err)
	}
	limit := uint64(64)
	options.TableLimit = &limit
	if usage, err := sudoku.EstimateResources(options); err != nil || usage.EstimatedBytes <= 32<<20 {
		t.Fatal("old fixed memory cap remains", usage, err)
	}
}
