//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	btls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
)

func TestTLSRealityUIConstructorsAndPortableExport(t *testing.T) {
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Fatal("Current JVM-generated security-mode fixtures are required")
	}
	files, err := filepath.Glob(filepath.Join(dir, "tls-reality-*.json"))
	if err != nil || len(files) != 62 {
		t.Fatalf("Expected 62 fresh mode/transition fixtures, got %d: %v", len(files), err)
	}
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			data, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			core, _ := modernTestCore(t, data)
			if err := core.Start(); err != nil {
				t.Fatal(err)
			}
			if _, err := ExportPortableConfig(string(data)); err != nil {
				t.Fatal(err)
			}
			var root struct{ Outbounds []map[string]json.RawMessage }
			if err := json.Unmarshal(data, &root); err != nil {
				t.Fatal(err)
			}
			raw := root.Outbounds[0]["tls"]
			if len(raw) == 0 {
				return
			}
			var options option.OutboundTLSOptions
			if err := sjson.Unmarshal(raw, &options); err != nil {
				t.Fatal(err)
			}
			config, err := btls.NewClient(context.Background(), log.NewNOPFactory().Logger(), "127.0.0.1", options)
			if err != nil {
				t.Fatal(err)
			}
			reality := strings.HasSuffix(strings.TrimSuffix(filepath.Base(file), ".json"), "-reality")
			actual := reflect.TypeOf(config).String()
			if reality != strings.Contains(actual, "RealityClientConfig") {
				t.Fatalf("Wrong actual TLS engine: %s", actual)
			}
			if config.ServerName() != "example.invalid" {
				t.Fatal("SNI was not retained by the actual client")
			}
		})
	}
}
