package libcore

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

func TestLegacyConfigSafeMigrationAndIdempotence(t *testing.T) {
	for _, input := range []string{
		`{"inbounds":[{"type":"tun","inet4_address":["172.19.0.1/28"],"inet6_address":"fd00::1/126","gso":false}],"outbounds":[{"type":"direct"}]}`,
		`{"dns":{"servers":[{"tag":"local","address":"local"}]}}`,
		`{"dns":{"servers":[{"tag":"old","address":"https://dns.example.invalid/dns-query","detour":"proxy","address_resolver":"bootstrap"}]} }`,
		`{"dns":{"servers":[{"type":"local","tag":"local"}],"rules":[{"outbound":["any"],"server":"local","strategy":"prefer_ipv6"}]}}`,
		`{"outbounds":[{"type":"socks","domain_resolver":"direct","domain_strategy":"ipv6_only"}]}`,
		`{"route":{"rule_set":[{"type":"remote","tag":"remote","url":"https://example.invalid/set.srs","download_detour":"proxy"}]}}`,
	} {
		out, err := MigrateConfig(input)
		if err != nil {
			t.Fatal(err)
		}
		again, err := MigrateConfig(out)
		if err != nil || again != out {
			t.Fatalf("migration is not idempotent: %v", err)
		}
		for _, legacy := range []string{"inet4_address", "inet6_address", "download_detour", "address_resolver", "domain_strategy", "\"address\":\"local\"", "\"outbound\":[\"any\"]"} {
			if strings.Contains(out, legacy) {
				t.Fatalf("legacy %s survived", legacy)
			}
		}
	}
}

func TestLegacyConfigAmbiguityHasJSONPath(t *testing.T) {
	cases := map[string]string{
		`{} {}`:                               "$",
		`{"dns":{"fakeip":{"enabled":true}}}`: "dns.fakeip",
		`{"dns":{"servers":[{"address":"https://dns.example.invalid"}]}}`:                         "dns.servers[0]",
		`{"dns":{"servers":[{"address":"local","strategy":"ipv4_only"}]}}`:                        "dns.servers[0]",
		`{"inbounds":[{"type":"tun","address":["10.0.0.1/24"],"inet4_address":["10.1.0.1/24"]}]}`: "inbounds[0].address",
		`{"inbounds":[{"type":"tun","gso":true}]}`:                                                "inbounds[0].gso",
		`{"dns":{"rules":[{"strategy":"ipv4_only","server":"dns"}]}}`:                             "$.dns.rules[0].strategy",
		`{"dns":{"rules":[{"outbound":["proxy"],"server":"dns"}]}}`:                               "$.dns.rules[0].outbound",
		`{"route":{"rules":[{"geoip":["cn"],"outbound":"direct"}]}}`:                              "$.route.rules[0].geoip",
		`{"outbounds":[{"type":"direct","override_address":"example.invalid"}]}`:                  "$.outbounds[0].override_address",
		`{"outbounds":[{"type":"socks","domain_strategy":"ipv6_only"}]}`:                          "outbounds[0].domain_strategy",
	}
	for input, path := range cases {
		if _, err := MigrateConfig(input); err == nil || !strings.Contains(err.Error(), path) {
			t.Fatalf("expected path %s, got %v", path, err)
		}
	}
	// Unknown fields must reach the upstream strict parser, not disappear.
	out, err := MigrateConfig(`{"unknown_user_option":true}`)
	if err != nil || !strings.Contains(out, "unknown_user_option") {
		t.Fatal("unknown field dropped")
	}
	if err = startCoreConfig(out, false); err == nil {
		t.Fatal("upstream unknown-field validation bypassed")
	}
}

func TestLegacyWireGuardRetainsDefaultAndImplicitIdentities(t *testing.T) {
	out, err := MigrateConfig(`{"outbounds":[{"type":"wireguard","local_address":["10.0.0.2/32"]},{"type":"direct"}]}`)
	if err != nil {
		t.Fatal(err)
	}
	var cfg map[string]any
	json.Unmarshal([]byte(out), &cfg)
	if cfg["route"].(map[string]any)["final"] != "0" || cfg["outbounds"].([]any)[0].(map[string]any)["tag"] != "1" {
		t.Fatal("implicit default/identities changed")
	}
	out, err = MigrateConfig(`{"route":{"final":"direct"},"outbounds":[{"type":"wireguard","tag":"wg"},{"type":"direct","tag":"direct"}]}`)
	if err != nil {
		t.Fatal(err)
	}
	json.Unmarshal([]byte(out), &cfg)
	if cfg["route"].(map[string]any)["final"] != "direct" {
		t.Fatal("explicit final overwritten")
	}
}

func TestPortableExportNeverDropsSniffOrGeoBehavior(t *testing.T) {
	if _, err := ExportPortableConfig(`{"inbounds":[{"type":"mixed","sniff":true,"sniff_override_destination":true}]}`); err == nil || !strings.Contains(err.Error(), "_neko_sniff_override_rules") {
		t.Fatal("sniff behavior silently lost")
	}
	prior := nekoutils.GetGeoSiteHeadlessRules
	defer func() { nekoutils.GetGeoSiteHeadlessRules = prior }()
	nekoutils.GetGeoSiteHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		if name != "test" {
			t.Fatal("Geo name changed")
		}
		return []option.HeadlessRule{{Type: "default", DefaultOptions: option.DefaultHeadlessRule{DomainSuffix: []string{"example.invalid"}}}}, nil
	}
	out, err := ExportPortableConfig(`{"route":{"rule_set":[{"type":"local","tag":"test","format":"binary","path":"geosite:test"}],"rules":[{"rule_set":["test"],"outbound":"direct"}]},"outbounds":[{"type":"direct","tag":"direct"}]}`)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out, "geosite:") || !strings.Contains(out, `"type":"inline"`) || !strings.Contains(out, "example.invalid") {
		t.Fatal("Geo conversion lost rules")
	}
	if err = startCoreConfig(out, false); err != nil {
		t.Fatal(err)
	}
	out, err = ExportPortableConfig(`{"inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","sniff":true}],"outbounds":[{"type":"direct","tag":"direct"}]}`)
	if err != nil || strings.Contains(out, "_neko_") {
		t.Fatalf("sniff-only export: %v", err)
	}
	if err = startCoreConfig(out, false); err != nil {
		t.Fatal(err)
	}
}
