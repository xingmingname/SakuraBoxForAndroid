package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

// Use the actual Android registries and real core lifecycle. No listening
// sockets, TUN device or external connection is required by these fixtures.
func startCoreConfig(config string, migrate bool) error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(),
		nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil),
		nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	data := []byte(config)
	var overrides []int
	var err error
	if migrate {
		data, overrides, err = prepareConfig(config)
		if err != nil {
			return err
		}
	}
	var opts option.Options
	if err = opts.UnmarshalJSONContext(ctx, data); err != nil {
		return fmt.Errorf("decode: %w", err)
	}
	instance, err := box.New(box.Options{Options: opts, Context: ctx})
	if err != nil {
		return fmt.Errorf("create: %w", err)
	}
	defer instance.Close()
	if err = applySniffOverrides(instance.Router().Rules(), overrides); err != nil {
		return err
	}
	return instance.Start()
}

func TestDNSStartReproducesThenFixesEmptyDirectDetour(t *testing.T) {
	config := `{"log":{"disabled":true},"dns":{"servers":[{"type":"https","tag":"dns-direct","server":"127.0.0.1","detour":"direct"}],"final":"dns-direct"},"outbounds":[{"type":"direct","tag":"direct"}]}`
	err := startCoreConfig(config, false)
	if err == nil || !strings.Contains(err.Error(), "detour to an empty direct outbound makes no sense") {
		t.Fatalf("expected exact reported START failure, got %v", err)
	}
	if err = startCoreConfig(config, true); err != nil {
		t.Fatalf("migrated Start failed: %v", err)
	}
}

func TestDNSDetourMigrationPreservesConfiguredAndProxyDetours(t *testing.T) {
	config := `{"dns":{"servers":[{"type":"https","tag":"one","server":"127.0.0.1","detour":"bound"},{"type":"udp","tag":"two","server":"127.0.0.1","detour":"proxy"}]},"outbounds":[{"type":"direct","tag":"bound","connect_timeout":"5s"},{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1080}]}`
	migrated, err := MigrateConfig(config)
	if err != nil {
		t.Fatal(err)
	}
	for _, detour := range []string{`"detour":"bound"`, `"detour":"proxy"`} {
		if !strings.Contains(migrated, detour) {
			t.Fatalf("lost custom dialer: %s", migrated)
		}
	}
	if err = startCoreConfig(migrated, true); err != nil {
		t.Fatal(err)
	}
}

func TestDNSDetourMigrationUsesActualDirectOptions(t *testing.T) {
	config := `{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"direct","tcp_fast_open":false,"connect_timeout":"0s"}],"dns":{"servers":[{"type":"https","tag":"dns-direct","server":"127.0.0.1","detour":"direct"}]}}`
	if err := startCoreConfig(config, false); err == nil || !strings.Contains(err.Error(), "empty direct outbound") {
		t.Fatalf("expected empty direct detour failure: %v", err)
	}
	if err := startCoreConfig(config, true); err != nil {
		t.Fatal(err)
	}
}

func TestCoreStartsProtocolAndDNSMatrix(t *testing.T) {
	outbounds := map[string]string{
		"hysteria2":     `{"type":"hysteria2","tag":"proxy","server":"127.0.0.1","server_port":443,"password":"test-only","tls":{"enabled":true,"server_name":"example.invalid"}}`,
		"vless-reality": `{"type":"vless","tag":"proxy","server":"127.0.0.1","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001","flow":"xtls-rprx-vision","tls":{"enabled":true,"server_name":"example.invalid","utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,"public_key":"BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc","short_id":"0123456789abcdef"}}}`,
		"vmess":         `{"type":"vmess","tag":"proxy","server":"127.0.0.1","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001","security":"auto"}`,
		"trojan":        `{"type":"trojan","tag":"proxy","server":"127.0.0.1","server_port":443,"password":"test-only","tls":{"enabled":true,"server_name":"example.invalid"}}`,
		"shadowsocks":   `{"type":"shadowsocks","tag":"proxy","server":"127.0.0.1","server_port":443,"method":"aes-128-gcm","password":"test-only"}`,
		"socks":         `{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1080}`,
		"http":          `{"type":"http","tag":"proxy","server":"127.0.0.1","server_port":8080}`,
	}
	for name, outbound := range outbounds {
		for _, dnsType := range []string{"udp", "tcp", "tls", "https", "quic", "h3"} {
			t.Run(name+"/"+dnsType, func(t *testing.T) {
				config := fmt.Sprintf(`{"log":{"disabled":true},"dns":{"servers":[{"type":"local","tag":"dns-local"},{"type":"https","tag":"dns-direct","server":"127.0.0.1","domain_resolver":"dns-local"},{"type":%q,"tag":"dns-remote","server":"dns.example.invalid","domain_resolver":"dns-direct","detour":"proxy"},{"type":"fakeip","tag":"dns-fake","inet4_range":"198.18.0.0/15","inet6_range":"fc00::/18"}],"rules":[{"inbound":["tun-in"],"query_type":["AAAA"],"action":"predefined","rcode":"NOERROR"},{"inbound":["tun-in"],"server":"dns-fake","disable_cache":true}],"final":"dns-remote","strategy":"prefer_ipv6"},"route":{"rules":[{"port":53,"action":"hijack-dns"},{"ip_is_private":true,"outbound":"direct"}],"final":"proxy"},"outbounds":[%s,{"type":"direct","tag":"direct"}]}`, dnsType, outbound)
				if err := startCoreConfig(config, true); err != nil {
					t.Fatal(err)
				}
			})
		}
	}
}

func TestProductionAndroidFixturesStart(t *testing.T) {
	directory := os.Getenv("NEKO_CONFIG_FIXTURES")
	if directory == "" {
		t.Skip("Run after Android JVM fixture generation with -ConfigFixtures")
	}
	files, err := filepath.Glob(filepath.Join(directory, "*.json"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no production fixtures: %v", err)
	}
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			data, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			var root map[string]any
			if err = json.Unmarshal(data, &root); err != nil {
				t.Fatal(err)
			}
			if err = startCoreConfig(string(data), true); err != nil {
				t.Fatal(err)
			}
			prepared, _, err := prepareConfig(string(data))
			if err != nil {
				t.Fatal(err)
			}
			if err = os.MkdirAll("migrated-fixtures", 0700); err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(filepath.Join("migrated-fixtures", filepath.Base(file)), prepared, 0600); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestCoreStartsInboundsGroupsAndInlineRuleSet(t *testing.T) {
	for _, kind := range []string{"socks", "http", "mixed"} {
		t.Run(kind, func(t *testing.T) {
			config := fmt.Sprintf(`{"log":{"disabled":true},"inbounds":[{"type":%q,"tag":"test-in","listen":"127.0.0.1"}],"outbounds":[{"type":"selector","tag":"proxy","outbounds":["node","auto"],"default":"node"},{"type":"urltest","tag":"auto","outbounds":["node"],"url":"http://127.0.0.1:1/","interval":"1h","idle_timeout":"2h"},{"type":"socks","tag":"node","server":"127.0.0.1","server_port":1080},{"type":"direct","tag":"direct"},{"type":"block","tag":"block"},{"type":"dns","tag":"legacy-dns"}],"dns":{"servers":[{"type":"udp","tag":"dns-direct","server":"127.0.0.1"}],"final":"dns-direct"},"route":{"rule_set":[{"type":"inline","tag":"test-set","rules":[{"domain_suffix":["example.invalid"]}]}],"rules":[{"port":53,"action":"hijack-dns"},{"rule_set":["test-set"],"outbound":"direct"},{"domain":["blocked.example.invalid"],"action":"reject"}],"final":"proxy"}}`, kind)
			if err := startCoreConfig(config, true); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestLegacyDNSOutboundMigrationAndUnsafeReferences(t *testing.T) {
	input := `{"outbounds":[{"type":"direct","tag":"direct"},{"type":"dns","tag":"old-dns"}],"route":{"rules":[{"port":53,"outbound":"old-dns"}],"final":"direct"}}`
	if err := startCoreConfig(input, false); err == nil || !strings.Contains(err.Error(), "dns outbound is deprecated") {
		t.Fatalf("expected native removal error: %v", err)
	}
	if err := startCoreConfig(input, true); err != nil {
		t.Fatal(err)
	}
	migrated, err := MigrateConfig(input)
	if err != nil {
		t.Fatal(err)
	}
	again, err := MigrateConfig(migrated)
	if err != nil || migrated != again {
		t.Fatalf("not idempotent: %v", err)
	}
	for _, input := range []string{
		`{"outbounds":[{"type":"dns","tag":"dns"},{"type":"selector","tag":"proxy","outbounds":["dns"]}]}`,
		`{"outbounds":[{"type":"dns","tag":"dns"}],"dns":{"servers":[{"type":"udp","server":"127.0.0.1","detour":"dns"}]}}`,
	} {
		if _, err = MigrateConfig(input); err == nil {
			t.Fatal("unsupported reference silently removed")
		}
	}
	for _, input := range []string{
		`{"outbounds":[{"type":"dns","tag":"dns"}],"route":{"final":"dns"}}`,
		`{"outbounds":[{"type":"dns"},{"type":"direct"}]}`,
	} {
		migrated, err := MigrateConfig(input)
		if err != nil || !strings.Contains(migrated, `"action":"hijack-dns"`) {
			t.Fatalf("default DNS behavior lost: %v %s", err, migrated)
		}
		if err = startCoreConfig(migrated, true); err != nil {
			t.Fatal(err)
		}
	}
}

func TestLegacyDNSMigrationRejectsInvalidShapesAndKeepsNumbers(t *testing.T) {
	for _, invalid := range []string{
		`{"outbounds":[{"type":"dns"}],"route":[]}`,
		`{"outbounds":[{"type":"dns"}],"route":{"rules":"bad"}}`,
		`{"outbounds":[{"type":"dns","tag":"d"},{"type":"direct","tag":"d"}]}`,
	} {
		if _, err := MigrateConfig(invalid); err == nil {
			t.Fatalf("invalid input silently changed: %s", invalid)
		}
	}
	// Migration is a JSON transformation and must not round unrelated integers.
	migrated, err := MigrateConfig(`{"outbounds":[{"type":"direct"},{"type":"dns","tag":"dns"}],"_test_integer":9007199254740993}`)
	if err != nil || !strings.Contains(migrated, "9007199254740993") {
		t.Fatalf("number changed: %v %s", err, migrated)
	}
}
