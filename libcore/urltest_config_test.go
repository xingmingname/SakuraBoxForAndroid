package libcore

import (
	"strings"
	"testing"
)

func TestPinnedNetworkConfigConflicts(t *testing.T) {
	check := func(config string) error {
		box, err := NewTestSingBoxInstance(config, new(pinnedResolver), newPinnedControl())
		if box != nil {
			box.Close()
		}
		return err
	}
	if err := check(`{"route":{"auto_detect_interface":true,"final":"B"},"outbounds":[{"type":"socks","tag":"B","server":"127.0.0.1","server_port":1080}]}`); err != nil {
		t.Fatal(err)
	}
	for _, config := range []string{
		`{"route":{"auto_detect_interface":false}}`,
		`{"route":{"auto_detect_interface":true},"outbounds":[{"type":"direct","bind_interface":"other"}]}`,
		`{"route":{"auto_detect_interface":true},"dns":{"servers":[{"type":"udp","server":"127.0.0.1","inet4_bind_address":"127.0.0.2"}]}}`,
		`{"route":{"auto_detect_interface":true,"default_interface":"other"}}`,
		`{"route":{"auto_detect_interface":true,"default_mark":123}}`,
	} {
		if err := check(config); err == nil {
			t.Fatalf("unsafe config accepted: %s", config)
		}
	}
	if err := check(`{"route":{"auto_detect_interface":true},"outbounds":[{"type":"direct","protect_path":"other"}]}`); err == nil || !strings.Contains(err.Error(), "outbounds[0].protect_path") {
		t.Fatalf("missing precise path: %v", err)
	}
}
