package libcore

import (
	"fmt"

	"github.com/sagernet/sing-box/option"
	"libcore/sudoku"
	sudokuo "libcore/sudoku/transport/obfs/sudoku"
)

// Keep node budgets independent; whole-config statistics still check overflow.
func validateSudokuResources(options option.Options) error {
	_, err := estimateSudokuResources(options)
	return err
}

func estimateSudokuResources(options option.Options) (sudokuo.TableResources, error) {
	var count sudokuo.TableResources
	for i, out := range options.Outbounds {
		if opts, ok := out.Options.(*sudoku.OutboundOptions); ok {
			n, err := sudoku.TableCount(*opts)
			if err == nil {
				err = count.Add(sudokuo.TableResources{PhysicalTables: n}, sudokuo.TableBudget{})
			}
			if err != nil {
				return sudokuo.TableResources{}, fmt.Errorf("outbounds[%d]: %w", i, err)
			}
		}
	}
	var total sudokuo.TableResources
	for i, out := range options.Outbounds {
		if opts, ok := out.Options.(*sudoku.OutboundOptions); ok {
			usage, err := sudoku.EstimateResources(*opts)
			if err == nil {
				err = total.Add(usage, sudokuo.TableBudget{})
			}
			if err != nil {
				return sudokuo.TableResources{}, fmt.Errorf("outbounds[%d]: %w", i, err)
			}
		}
	}
	return total, nil
}
