package libcore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestSudokuURIJVMOutboundFixtures(t *testing.T) {
	directory := os.Getenv("SUDOKU_URI_JVM_FIXTURES")
	if directory == "" {
		t.Skip("URI JVM fixtures not supplied")
	}
	paths, err := filepath.Glob(filepath.Join(directory, "*.json"))
	if err != nil || len(paths) == 0 {
		t.Fatalf("Missing URI fixtures: %v", err)
	}
	for _, path := range paths {
		t.Run(filepath.Base(path), func(t *testing.T) {
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			var outbound map[string]any
			if err := json.Unmarshal(data, &outbound); err != nil {
				t.Fatal(err)
			}
			outbound["tag"] = "proxy"
			config := sudokuTestConfig(outbound)
			if err := CheckConfig(string(config)); err != nil {
				t.Fatal(err)
			}
			// Never dial a real shared endpoint. Only the test server address changes;
			// all imported protocol fields pass through the actual registered core.
			outbound["server"] = "127.0.0.1"
			outbound["server_port"] = 9
			if err := startCoreConfig(string(sudokuTestConfig(outbound)), true); err != nil {
				t.Fatal(err)
			}
			if _, err := ExportPortableConfig(string(config)); err == nil {
				t.Fatal("Sudoku must still refuse official sing-box export")
			}
		})
	}
}
