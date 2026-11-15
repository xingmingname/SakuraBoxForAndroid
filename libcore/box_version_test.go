package libcore

import (
	"runtime"
	"runtime/debug"
	"strings"
	"testing"
	"unicode"

	"github.com/sagernet/sing-box/common/badversion"
	"github.com/sagernet/sing-box/constant"
	"golang.org/x/mod/semver"
)

func TestVersionBoxReportsSakuraCoreAndCompilerMetadata(t *testing.T) {
	if constant.Version != "1.14.3-sakura" {
		t.Fatalf("unexpected local core version: %q", constant.Version)
	}
	lines := strings.Split(VersionBox(), "\n")
	if len(lines) != 3 || lines[0] != "sing-box: "+constant.Version {
		t.Fatalf("unexpected version lines: %q", lines)
	}
	if lines[1] != runtime.Version()+"@"+runtime.GOOS+"/"+runtime.GOARCH {
		t.Fatalf("compiler/platform metadata changed: %q", lines[1])
	}
	features := strings.Split(lines[2], ",")
	if len(features) < 2 || features[len(features)-2] != "with_sudoku" || features[len(features)-1] != "with_nakis" {
		t.Fatalf("missing Sudoku or Nakis feature: %q", lines[2])
	}
	var actualTags []string
	if info, ok := debug.ReadBuildInfo(); ok {
		for _, setting := range info.Settings {
			if setting.Key == "-tags" {
				for _, tag := range strings.FieldsFunc(setting.Value, func(r rune) bool { return r == ',' || unicode.IsSpace(r) }) {
					if tag != "with_sudoku" && tag != "with_nakis" {
						actualTags = append(actualTags, tag)
					}
				}
			}
		}
	}
	if strings.Join(features[:len(features)-2], ",") != strings.Join(actualTags, ",") {
		t.Fatalf("build tags lost or fabricated: %q versus %q", features, actualTags)
	}
	if strings.Count(","+lines[2]+",", ",with_sudoku,") != 1 || strings.Count(","+lines[2]+",", ",with_nakis,") != 1 {
		t.Fatalf("duplicate Sudoku or Nakis feature: %q", lines[2])
	}
}

func TestCoreBuildNameKeepsSemanticBaseForCompatibilityChecks(t *testing.T) {
	if !semver.IsValid("v" + constant.Version) {
		t.Fatal("feature display must not change the core semantic version")
	}
	base := badversion.Parse(constant.Version)
	if base.Major != 1 || base.Minor != 14 || base.Patch != 3 {
		t.Fatalf("base version changed: %+v", base)
	}
	if strings.Split(VersionBox(), "\n")[0] != "sing-box: 1.14.3-sakura" {
		t.Fatalf("base version display changed: %s", VersionBox())
	}
}

func TestVersionFeaturesPreserveTagsAndAppendFeaturesOnce(t *testing.T) {
	const base = "with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"
	for _, test := range []struct{ name, input, want string }{
		{"no build metadata", "", "with_sudoku,with_nakis"},
		{"current build", base, base + ",with_sudoku,with_nakis"},
		{"already present", base + ",with_sudoku", base + ",with_sudoku,with_nakis"},
		{"middle and repeated", "with_sudoku,with_utls,with_sudoku,with_quic,with_sudoku", "with_utls,with_quic,with_sudoku,with_nakis"},
		{"repeated Nakis", "with_nakis,with_quic,with_nakis", "with_quic,with_sudoku,with_nakis"},
		{"spaces and unknown features", " extra_tag\twith_utls,\nwith_quic  ", "extra_tag,with_utls,with_quic,with_sudoku,with_nakis"},
		{"only marker", "with_sudoku,with_sudoku", "with_sudoku,with_nakis"},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := versionFeatures(test.input); got != test.want {
				t.Fatalf("got %q, want %q", got, test.want)
			}
		})
	}
}
