package libcore

import (
	"strings"
	"testing"
)

func TestInternalCheckAllowsSudokuWithoutWeakeningOfficialExport(t *testing.T) {
	config := `{"outbounds":[{"type":"sudoku","tag":"proxy","server":"127.0.0.1","server_port":443,"key":"test-only","padding_min":0,"enable_pure_downlink":false}]}`
	if err := CheckConfig(config); err != nil {
		t.Fatal(err)
	}
	if _, err := ExportPortableConfig(config); err == nil || !strings.Contains(err.Error(), "official sing-box") {
		t.Fatalf("official export: %v", err)
	}
	for _, invalid := range []string{
		`{"inbounds":[{"type":"mixed","unexpected":true}],"outbounds":[{"type":"direct"}]}`,
		`{"outbounds":[{"type":"sudoku","unexpected":true}]}`,
	} {
		if err := CheckConfig(invalid); err == nil {
			t.Fatalf("accepted unknown field: %s", invalid)
		}
	}
}
