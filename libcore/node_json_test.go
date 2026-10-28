//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestNodeJSONGeneratedFixturesAndConstructor(t *testing.T) {
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Fatal("Current JVM node JSON fixtures are required")
	}
	for _, name := range []string{"node-json-full.json", "node-json-test.json"} {
		t.Run(name, func(t *testing.T) {
			data, err := os.ReadFile(filepath.Join(dir, "node-json", name))
			if err != nil {
				t.Fatal(err)
			}
			if _, err = ExportPortableConfig(string(data)); err != nil {
				t.Fatal(err)
			}
			var root map[string]json.RawMessage
			if err = json.Unmarshal(data, &root); err != nil {
				t.Fatal(err)
			}
			if name == "node-json-full.json" {
				var ins []map[string]any
				if err = json.Unmarshal(root["inbounds"], &ins); err != nil || len(ins) != 1 || ins[0]["stack"] != "system" {
					t.Fatal("Full JSON TUN stack was not preserved", err)
				}
			}
			root["inbounds"] = json.RawMessage(`[]`)
			delete(root, "experimental")
			delete(root, "services")
			root["log"] = json.RawMessage(`{"disabled":true}`)
			data, _ = json.Marshal(root)
			core, _ := modernTestCore(t, data)
			if err = core.Start(); err != nil {
				t.Fatal(err)
			}
			if _, ok := core.Outbound().Outbound("user-tag"); !ok {
				t.Fatal("User outbound tag lost")
			}
		})
	}
}

func TestNodeJSONStrictSchemaRejectsUnsupportedFields(t *testing.T) {
	for _, text := range []string{
		`{"unsupported_field":true,"outbounds":[{"type":"direct","tag":"d"}]}`,
		`{"outbounds":[{"type":"direct","tag":"d","unsupported_field":true}]}`,
		`{"outbounds":[{"type":"future-unknown-protocol","tag":"d"}]}`,
		`{"outbounds":[{"type":"http","tag":"p","server":"127.0.0.1","server_port":"443"}]}`,
	} {
		if _, err := ExportPortableConfig(text); err == nil {
			t.Fatal("Invalid JSON schema accepted")
		}
	}
}
