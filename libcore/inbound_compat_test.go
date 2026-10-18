package libcore

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	R "github.com/sagernet/sing-box/route/rule"
	"github.com/sagernet/sing/service"
)

func TestLegacyInboundProductionMigration(t *testing.T) {
	// Production uses this same migrator after ConfigBuilder merges node and
	// global JSON overrides, and again at NewSingBoxInstance's native boundary.
	for _, strategy := range []string{"as_is", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only"} {
		for mode := 0; mode <= 2; mode++ {
			for _, kind := range []string{"mixed", "tun"} {
				input := map[string]any{"inbounds": []any{map[string]any{"type": kind, "tag": "tun-in", "sniff": mode > 0, "sniff_override_destination": mode == 2, "sniff_timeout": "300ms", "domain_strategy": strategy, "udp_disable_domain_unmapping": true}}, "route": map[string]any{"rules": []any{map[string]any{"protocol": "dns", "action": "hijack-dns"}}}}
				raw, _ := json.Marshal(input)
				migrated, err := MigrateConfig(string(raw))
				if err != nil {
					t.Fatal(err)
				}
				twice, err := MigrateConfig(migrated)
				if err != nil || twice != migrated {
					t.Fatalf("not idempotent: %v", err)
				}
				data, overrides, err := prepareConfig(migrated)
				if err != nil {
					t.Fatal(err)
				}
				if strings.Contains(string(data), sniffOverrideMetadata) || strings.Contains(string(data), "sniff_override_destination") || strings.Contains(string(data), "domain_strategy") {
					t.Fatal("legacy fields reached native decoder")
				}
				ctx := service.ContextWith[option.InboundOptionsRegistry](context.Background(), nekoboxAndroidInboundRegistry())
				var options option.Options
				if err = options.UnmarshalJSONContext(ctx, data); err != nil {
					t.Fatalf("%s mode=%d strategy=%s: %v", kind, mode, strategy, err)
				}
				var rules []adapter.Rule
				for _, ruleOptions := range options.Route.Rules {
					rule, err := R.NewRule(ctx, log.NewNOPFactory().Logger(), ruleOptions, true)
					if err != nil {
						t.Fatal(err)
					}
					rules = append(rules, rule)
				}
				if err = applySniffOverrides(rules, overrides); err != nil {
					t.Fatal(err)
				}
				if mode > 0 {
					action := rules[0].Action().(*R.RuleActionSniff)
					if action.OverrideDestination != (mode == 2) {
						t.Fatal("sniff mode changed")
					}
					if !rules[0].Match(&adapter.InboundContext{Inbound: "tun-in"}) || rules[0].Match(&adapter.InboundContext{Inbound: "other"}) {
						t.Fatal("inbound isolation lost")
					}
				}
				if mode == 2 && len(overrides) != 1 || mode != 2 && len(overrides) != 0 {
					t.Fatal("unexpected override indices")
				}
				if rules[len(rules)-1].Action().Type() != "hijack-dns" {
					t.Fatal("original DNS rule order changed")
				}
			}
		}
	}
}

func TestLegacyInboundCustomJSONValidation(t *testing.T) {
	for _, input := range []string{
		`{"inbounds":[{"type":"mixed","sniff":"true"}]}`,
		`{"inbounds":[{"type":"tun","domain_strategy":"invalid"}]}`,
		`{"inbounds":[{"type":"tun","sniff_timeout":"invalid"}]}`,
		`{"inbounds":[{"type":"mixed","tag":"same"},{"type":"mixed","tag":"same"}]}`,
		`{"_neko_sniff_override_rules":[0],"route":{"rules":[{"action":"reject"}]}}`,
		`{"_neko_sniff_override_rules":[-1]}`, `null`, `[]`,
	} {
		if _, err := MigrateConfig(input); err == nil {
			t.Fatalf("invalid configuration accepted: %s", input)
		}
	}
	output, _, err := prepareConfig(`{"inbounds":[{"type":"mixed","sniff":true}],"route":{"rules":[{"inbound":["0"],"action":"reject"}]}}`)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(output), `"inbound":["0"]`) || strings.Contains(string(output), "neko-legacy") {
		t.Fatal("implicit inbound identity changed")
	}
}
