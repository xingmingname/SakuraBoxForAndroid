package tunnel

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"math/bits"
	"sync"
	"testing"

	"libcore/sudoku/transport/obfs/sudoku"
)

type tablePickFailure struct{ reads int }

func (r *tablePickFailure) Read([]byte) (int, error) {
	r.reads++
	return 0, io.ErrUnexpectedEOF
}
func tablePickInput(count, value int) []byte {
	width := (bits.Len(uint(count-1)) + 7) / 8
	out := make([]byte, width)
	for i := width - 1; i >= 0; i-- {
		out[i] = byte(value)
		value >>= 8
	}
	return out
}
func tablePickCandidates(count int) []*sudoku.Table {
	out := make([]*sudoku.Table, count)
	for i := range out {
		out[i] = new(sudoku.Table)
	}
	return out
}

func TestPickTableAllCandidateIndices(t *testing.T) {
	for _, count := range []int{0, 1, 3, 32, 64, 128, 255, 256, 257, 512, 1000} {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			d := &BaseDialer{Tables: tablePickCandidates(count)}
			if count == 0 {
				fail := new(tablePickFailure)
				if _, _, _, err := d.pickTableWithReader(fail); err == nil || fail.reads != 0 {
					t.Fatal("empty candidates must fail without entropy")
				}
				return
			}
			for idx := range count {
				fail := new(tablePickFailure)
				var input io.Reader = bytes.NewReader(tablePickInput(count, idx))
				if count == 1 {
					input = fail
				}
				got, hint, hasHint, err := d.pickTableWithReader(input)
				if err != nil || got != d.Tables[idx] || hint != got.Hint() || hasHint != (count > 1) {
					t.Fatalf("index %d: selected wrong candidate or hint: %v", idx, err)
				}
				if count == 1 && fail.reads != 0 {
					t.Fatal("one candidate must not read entropy")
				}
			}
		})
	}
}

func TestPickTableRejectionHasNoModuloBias(t *testing.T) {
	for _, count := range []int{3, 32, 64, 128, 255, 256, 257, 512, 1000} {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			d := &BaseDialer{Tables: tablePickCandidates(count)}
			hits := make([]int, count)
			domain := 1 << bits.Len(uint(count-1))
			for value := range domain {
				data := append(tablePickInput(count, value), tablePickInput(count, 1)...)
				input := bytes.NewReader(data)
				got, _, _, err := d.pickTableWithReader(input)
				if err != nil {
					t.Fatal(err)
				}
				want := value
				consumed := len(tablePickInput(count, value))
				if value >= count {
					want = 1
					consumed *= 2
				}
				if got != d.Tables[want] || input.Len() != len(data)-consumed {
					t.Fatalf("value %d must reject outside range rather than take remainder", value)
				}
				if value < count {
					hits[value]++
				}
			}
			for idx, hit := range hits {
				if hit != 1 {
					t.Fatalf("candidate %d has %d accepted representations", idx, hit)
				}
			}
		})
	}
}

func TestPickTableEntropyErrors(t *testing.T) {
	d := &BaseDialer{Tables: tablePickCandidates(3)}
	for _, input := range []io.Reader{new(tablePickFailure), bytes.NewReader(nil), bytes.NewReader([]byte{3})} {
		if _, _, _, err := d.pickTableWithReader(input); !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
			t.Fatalf("entropy failure must propagate: %v", err)
		}
	}
}

func TestPickTableDuplicateWeightsAndOrder(t *testing.T) {
	a, b := new(sudoku.Table), new(sudoku.Table)
	d := &BaseDialer{Tables: []*sudoku.Table{a, a, b}}
	hits := map[*sudoku.Table]int{}
	for index := range 3 {
		got, _, _, err := d.pickTableWithReader(bytes.NewReader(tablePickInput(3, index)))
		if err != nil {
			t.Fatal(err)
		}
		hits[got]++
	}
	if hits[a] != 2 || hits[b] != 1 || d.Tables[0] != a || d.Tables[1] != a || d.Tables[2] != b {
		t.Fatal("candidate duplicates, weights or order changed")
	}
}

func TestPickTableConcurrentCryptoReader(t *testing.T) {
	d := &BaseDialer{Tables: tablePickCandidates(1000)}
	valid := make(map[*sudoku.Table]bool, len(d.Tables))
	for _, table := range d.Tables {
		valid[table] = true
	}
	var wg sync.WaitGroup
	failures := make(chan error, 16)
	for range 16 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 128 {
				got, hint, hasHint, err := d.pickTable()
				if err != nil || !valid[got] || !hasHint || hint != got.Hint() {
					failures <- fmt.Errorf("concurrent selection failed: %v", err)
					return
				}
			}
		}()
	}
	wg.Wait()
	close(failures)
	for err := range failures {
		t.Error(err)
	}
}

func TestPickTableRetainsDirectionalAndPrivateKeyState(t *testing.T) {
	for _, mode := range []string{"ascii", "prefer_ascii", "entropy", "up_ascii_down_entropy", "up_entropy_down_ascii"} {
		t.Run(mode, func(t *testing.T) {
			first, err := sudoku.NewTableWithCustom("selection-test-a", mode, "")
			if err != nil {
				t.Fatal(err)
			}
			last, err := sudoku.NewTableWithCustom("selection-test-b", mode, "")
			if err != nil {
				t.Fatal(err)
			}
			private := []byte("synthetic-split-key-state")
			d := &BaseDialer{Tables: []*sudoku.Table{first, last}, PrivateKey: bytes.Clone(private)}
			got, hint, hasHint, err := d.pickTableWithReader(bytes.NewReader([]byte{1}))
			if err != nil || got != last || hint != last.Hint() || !hasHint || !bytes.Equal(d.PrivateKey, private) {
				t.Fatalf("table direction/hint or split-key state changed: %v", err)
			}
		})
	}
}
