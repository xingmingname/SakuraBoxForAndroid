package libcore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	sudokuc "libcore/sudoku/transport/crypto"
)

func TestSudokuUIGeneratedFixturesStartTheActualCore(t *testing.T) {
	directory := os.Getenv("NEKO_CONFIG_FIXTURES")
	if directory == "" {
		t.Skip("Current JVM-generated Sudoku UI fixtures not supplied")
	}
	paths, err := filepath.Glob(filepath.Join(directory, "sudoku-ui-*.json"))
	if err != nil || len(paths) == 0 {
		t.Fatalf("Missing Sudoku UI production fixtures: %v", err)
	}
	for _, path := range paths {
		t.Run(filepath.Base(path), func(t *testing.T) {
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			if err := CheckConfig(string(data)); err != nil {
				t.Fatal(err)
			}
			if filepath.Base(path) == "sudoku-ui-split-key.json" || filepath.Base(path) == "sudoku-ui-master-key.json" {
				var root struct {
					Outbounds []struct {
						Key string `json:"key"`
					} `json:"outbounds"`
				}
				if err := json.Unmarshal(data, &root); err != nil {
					t.Fatal(err)
				}
				if len(root.Outbounds) != 1 {
					t.Fatal("Expected one generated outbound")
				}
				if _, err := sudokuc.RecoverPublicKey(root.Outbounds[0].Key); err != nil {
					t.Fatalf("UI-generated private key not recognized by actual split-key implementation: %v", err)
				}
			}
			if err := startCoreConfig(string(data), true); err != nil {
				t.Fatal(err)
			}
		})
	}
}
