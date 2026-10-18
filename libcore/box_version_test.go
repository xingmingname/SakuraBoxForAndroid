package libcore

import (
	"runtime"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/constant"
)

func TestVersionBoxReportsSakuraCoreAndCompilerMetadata(t *testing.T) {
	if constant.Version != "1.14.3-sakura" {
		t.Fatalf("unexpected local core version: %q", constant.Version)
	}
	lines := strings.Split(VersionBox(), "\n")
	if len(lines) < 2 || lines[0] != "sing-box: "+constant.Version {
		t.Fatalf("version output does not reflect the core constant: %q", lines)
	}
	if lines[1] != runtime.Version()+"@"+runtime.GOOS+"/"+runtime.GOARCH {
		t.Fatalf("compiler/platform metadata changed: %q", lines[1])
	}
}
