package sudoku

import (
	"strings"

	sudokut "libcore/sudoku/transport"
	sudokuo "libcore/sudoku/transport/obfs/sudoku"
)

// Match buildProtocolTables' CLIENT precedence exactly. Do not rewrite options:
// a nonempty list wins over the single table, even when entries repeat/are empty.
func selectedClientPatterns(options OutboundOptions) []string {
	if len(options.CustomTables) != 0 {
		return options.CustomTables
	}
	if pattern := strings.TrimSpace(options.CustomTable); pattern != "" {
		return []string{pattern}
	}
	return nil
}

func TableCount(options OutboundOptions) (uint64, error) {
	budget, err := ResourceBudget(options)
	if err != nil {
		return 0, err
	}
	patterns := selectedClientPatterns(options)
	physical, err := sudokuo.CheckTableCount(resolveTableType(options.ASCII), len(patterns), budget)
	if err != nil {
		return 0, err
	}
	if err = sudokuo.CheckTableInputs(options.Key, patterns, physical, budget); err != nil {
		return 0, err
	}
	return physical, nil
}

func EstimateResources(options OutboundOptions) (sudokuo.TableResources, error) {
	patterns := selectedClientPatterns(options)
	if _, err := TableCount(options); err != nil {
		return sudokuo.TableResources{}, err
	}
	budget, err := ResourceBudget(options)
	if err != nil {
		return sudokuo.TableResources{}, err
	}
	return sudokuo.EstimateTableResources(sudokut.ClientAEADSeed(options.Key), resolveTableType(options.ASCII), patterns, budget)
}

func ResourceBudget(options OutboundOptions) (sudokuo.TableBudget, error) {
	return sudokuo.BudgetForLimit(options.TableLimit)
}
