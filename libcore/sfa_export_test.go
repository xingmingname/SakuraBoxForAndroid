package libcore

import (
	"context"
	"encoding/json"
	"net/netip"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
)

// This is the strict upstream decoder, without prepareConfig/migration.
func decodeSFAJSON(t *testing.T, text string) option.Options {
	t.Helper()
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	var opts option.Options
	if err := opts.UnmarshalJSONContext(ctx, []byte(text)); err != nil {
		t.Fatal(err)
	}
	return opts
}

func sfaJSONValue(t *testing.T, text string) map[string]any {
	t.Helper()
	var value map[string]any
	if err := json.Unmarshal([]byte(text), &value); err != nil {
		t.Fatal(err)
	}
	return value
}

func TestSFAExportDefaultsOnlyTouchMissingTunAutoRoute(t *testing.T) {
	cases := map[string]string{
		"single":            `[{"type":"tun","tag":"tun-in","address":["172.19.0.1/28"]}]`,
		"custom-tag":        `[{"type":"tun","tag":"my-custom-tunnel","address":["172.19.0.1/28"]}]`,
		"untagged":          `[{"type":"tun","address":["172.19.0.1/28"]}]`,
		"no-tun":            `[{"type":"mixed","tag":"local","listen":"127.0.0.1"},{"type":"direct","tag":"forward","listen":"127.0.0.1","override_address":"127.0.0.1","override_port":80}]`,
		"empty":             `[]`,
		"existing-true":     `[{"type":"tun","auto_route":true,"address":["172.19.0.1/28"]}]`,
		"explicit-false":    `[{"type":"tun","auto_route":false,"address":["172.19.0.1/28"]}]`,
		"multiple":          `[{"type":"mixed","tag":"local","listen":"127.0.0.1"},{"type":"tun","tag":"a","address":["172.19.0.1/28"]},{"type":"tun","tag":"b","auto_route":false,"address":["fd00::1/126"]}]`,
		"explicit-native":   `[{"type":"tun","dns_mode":"native","address":["172.19.0.1/28"]}]`,
		"explicit-disabled": `[{"type":"tun","dns_mode":"disabled","auto_route":false,"address":["172.19.0.1/28"]}]`,
		"custom-routes":     `[{"type":"tun","route_address":["192.0.2.0/24"],"route_exclude_address":["192.0.2.1/32"],"dns_address":["172.19.0.2"],"address":["172.19.0.1/28"]}]`,
		"ipv6-only":         `[{"type":"tun","address":["fd00::1/126"]}]`,
	}
	for name, inbounds := range cases {
		t.Run(name, func(t *testing.T) {
			input := `{"inbounds":` + inbounds + `,"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"},"dns":{"servers":[{"type":"local","tag":"local"}],"final":"local"}}`
			internal, err := MigrateConfig(input)
			if err != nil {
				t.Fatal(err)
			}
			expected := sfaJSONValue(t, internal)
			for _, value := range expected["inbounds"].([]any) {
				inbound := value.(map[string]any)
				if inbound["type"] == "tun" {
					if _, explicit := inbound["auto_route"]; !explicit {
						inbound["auto_route"] = true
					}
				}
			}
			out, err := ExportPortableConfig(internal)
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(expected, sfaJSONValue(t, out)) {
				t.Fatal("official export changed fields other than missing TUN auto_route")
			}
			decodeSFAJSON(t, out)
			again, err := MigrateConfig(input)
			if err != nil || again != internal {
				t.Fatal("official export affected internal configuration")
			}
			idempotent, err := ExportPortableConfig(out)
			if err != nil || idempotent != out {
				t.Fatal("official export is not idempotent")
			}
		})
	}
	withoutInbounds := `{"outbounds":[{"type":"direct","tag":"direct"}]}`
	out, err := ExportPortableConfig(withoutInbounds)
	if err != nil {
		t.Fatal(err)
	}
	if _, present := sfaJSONValue(t, out)["inbounds"]; present {
		t.Fatal("inbounds invented for non-TUN config")
	}
	decodeSFAJSON(t, out)
}

func TestSFATunRoutingAndDNSMinimalDifferences(t *testing.T) {
	for _, tc := range []struct {
		name, fields, dns string
		routes            bool
	}{
		{"baseline", "", "disabled", false},
		{"auto-route-only", `,"auto_route":true`, "hijack", true},
		{"dns-mode-only", `,"dns_mode":"hijack"`, "hijack", false},
		{"both", `,"auto_route":true,"dns_mode":"hijack"`, "hijack", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			text := `{"inbounds":[{"type":"tun","tag":"sfa-test","address":["172.19.0.1/28","fd00::1/126"]` + tc.fields + `}],"outbounds":[{"type":"direct","tag":"direct"}]}`
			decoded := decodeSFAJSON(t, text).Inbounds[0].Options.(*option.TunInboundOptions)
			// Same dependency routines invoked by libbox, with no TUN creation or VPN claims.
			opts := tun.Options{AutoRoute: decoded.AutoRoute, DNSMode: decoded.DNSMode}
			for _, prefix := range decoded.Address {
				if prefix.Addr().Is4() {
					opts.Inet4Address = append(opts.Inet4Address, prefix)
				} else {
					opts.Inet6Address = append(opts.Inet6Address, prefix)
				}
			}
			routes, err := opts.BuildAutoRouteRanges(true)
			if err != nil {
				t.Fatal(err)
			}
			if opts.DNSModeOrDefault() != tc.dns || (len(routes) > 0) != tc.routes {
				t.Fatalf("unexpected defaults: DNS=%s routes=%v", opts.DNSModeOrDefault(), routes)
			}
			if tc.routes && !reflect.DeepEqual(routes, []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0"), netip.MustParsePrefix("::/0")}) {
				t.Fatal("missing dual-stack default routes")
			}
			t.Logf("auto_route=%v dns_mode_raw=%q effective_dns=%s route_ranges=%v; no Android VPN execution", opts.AutoRoute, opts.DNSMode, opts.DNSModeOrDefault(), routes)
			if err = os.MkdirAll("sfa-minimal-variants", 0700); err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(filepath.Join("sfa-minimal-variants", tc.name+".json"), []byte(text), 0600); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestSFAOfficialExportProductionProtocolFixtures(t *testing.T) {
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Skip("requires JVM production fixtures")
	}
	files, err := filepath.Glob(filepath.Join(dir, "*.json"))
	if err != nil || len(files) == 0 {
		t.Fatal("missing fixtures")
	}
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			input, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			out, err := ExportPortableConfig(string(input))
			if err != nil {
				t.Fatal(err)
			}
			decodeSFAJSON(t, out)
			if err = startCoreConfig(out, true); err != nil {
				t.Fatal(err)
			}
			if strings.Contains(out, "_neko_") {
				t.Fatal("private export metadata")
			}
			if err = os.MkdirAll("official-export-fixtures", 0700); err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(filepath.Join("official-export-fixtures", filepath.Base(file)), []byte(out), 0600); err != nil {
				t.Fatal(err)
			}
		})
	}
}
