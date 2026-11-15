package libcore

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNakisInternalCheckAndPortableExport(t *testing.T) {
	const node = `{"outbounds":[{"type":"nakis","tag":"proxy","server":"127.0.0.1","server_port":443,"uuid":"6c1c4a52-3b8e-4d9a-9a52-0d4f5f0f7a11","tls":false,"pool_max_uses":0}]}`
	if err := CheckConfig(node); err != nil {
		t.Fatal(err)
	}
	if _, err := ExportPortableConfig(node); err == nil || !strings.Contains(err.Error(), "nakis") {
		t.Fatalf("portable export: %v", err)
	}
	bad := strings.Replace(node, `"tls":false`, `"tls":true`, 1)
	if err := CheckConfig(bad); err == nil || !strings.Contains(err.Error(), "SNI") {
		t.Fatalf("bad TLS options: %v", err)
	}
}

func TestNakisOfficialURIFixturesFromJVM(t *testing.T) {
	directory := os.Getenv("NAKIS_URI_JVM_FIXTURES")
	if directory == "" {
		t.Skip("set NAKIS_URI_JVM_FIXTURES to the JVM URI fixture output")
	}
	for _, name := range []string{"plain", "tls", "pin", "ech", "advanced", "server-pin"} {
		t.Run(name, func(t *testing.T) {
			raw, err := os.ReadFile(filepath.Join(directory, name+".json"))
			if err != nil {
				t.Fatal(err)
			}
			if err := CheckConfig(`{"outbounds":[` + string(raw) + `]}`); err != nil {
				t.Fatal(err)
			}
		})
	}
}
