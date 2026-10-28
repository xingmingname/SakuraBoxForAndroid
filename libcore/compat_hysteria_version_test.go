//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

func TestCompatHysteriaVersionSwitchGeneratedCore(t *testing.T) {
	directory := os.Getenv("NEKO_CONFIG_FIXTURES")
	if directory == "" {
		t.Fatal("Current JVM fixtures are required")
	}
	for _, transport := range []int{0, 1, 2} {
		t.Run(strconv.Itoa(transport), func(t *testing.T) {
			data, err := os.ReadFile(filepath.Join(directory, "ui-cd-hysteria2-version-switch-"+strconv.Itoa(transport)+".json"))
			if err != nil {
				t.Fatal(err)
			}
			core, _ := modernTestCore(t, data)
			if err := core.Start(); err != nil {
				t.Fatal(err)
			}
		})
	}
}
