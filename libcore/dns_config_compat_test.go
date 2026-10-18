package libcore

import (
	"context"
	"testing"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

type dnsConfigTestRegistry struct{}

func (dnsConfigTestRegistry) OptionTypes() []string {
	return []string{
		C.DNSTypeLocal,
		C.DNSTypeUDP,
		C.DNSTypeTCP,
		C.DNSTypeTLS,
		C.DNSTypeHTTPS,
		C.DNSTypeQUIC,
		C.DNSTypeHTTP3,
		C.DNSTypeDHCP,
		C.DNSTypeFakeIP,
	}
}

func (dnsConfigTestRegistry) CreateOptions(serverType string) (any, bool) {
	switch serverType {
	case C.DNSTypeLocal:
		return new(option.LocalDNSServerOptions), true
	case C.DNSTypeUDP, C.DNSTypeTCP:
		return new(option.RemoteDNSServerOptions), true
	case C.DNSTypeTLS, C.DNSTypeQUIC:
		return new(option.RemoteTLSDNSServerOptions), true
	case C.DNSTypeHTTPS, C.DNSTypeHTTP3:
		return new(option.RemoteHTTPSDNSServerOptions), true
	case C.DNSTypeDHCP:
		return new(option.DHCPDNSServerOptions), true
	case C.DNSTypeFakeIP:
		return new(option.FakeIPDNSServerOptions), true
	default:
		return nil, false
	}
}

// This mirrors ConfigBuilder's NekoBox DNS output: explicit server types,
// legacy per-server strategy moved to DNS routing, and FakeIP ranges on server.
const nekoboxDNSConfig = `{
	"dns": {
		"servers": [
			{"type":"local","tag":"dns-local","detour":"direct"},
			{"type":"https","tag":"dns-direct","server":"223.5.5.5","path":"/dns-query","domain_resolver":"dns-local","detour":"direct"},
			{"type":"https","tag":"dns-remote","server":"dns.google","path":"/dns-query","domain_resolver":"dns-direct"},
			{"type":"fakeip","tag":"dns-fake","inet4_range":"198.18.0.0/15","inet6_range":"fc00::/18"},
			{"type":"udp","tag":"dns-test-udp","server":"1.1.1.1"},
			{"type":"tcp","tag":"dns-test-tcp","server":"1.1.1.1"},
			{"type":"tls","tag":"dns-test-tls","server":"dns.example"},
			{"type":"quic","tag":"dns-test-quic","server":"dns.example"},
			{"type":"h3","tag":"dns-test-h3","server":"dns.example"},
			{"type":"dhcp","tag":"dns-test-dhcp","interface":"wlan0"}
		],
		"rules": [
			{"inbound":["tun-in"],"server":"dns-fake","disable_cache":true,"strategy":"ipv4_only"},
			{"outbound":["any"],"server":"dns-direct","strategy":"prefer_ipv4"},
			{"domain":["blocked.example"],"action":"predefined","rcode":"NOERROR"}
		],
		"final":"dns-remote",
		"strategy":"prefer_ipv4"
	}
}`

func TestNekoBoxDNSConfigAcceptedBySingBox1143(t *testing.T) {
	t.Parallel()

	ctx := service.ContextWith[option.DNSTransportOptionsRegistry](context.Background(), dnsConfigTestRegistry{})
	var parsed option.Options
	if err := parsed.UnmarshalJSONContext(ctx, []byte(nekoboxDNSConfig)); err != nil {
		t.Fatalf("sing-box 1.14.3 rejected generated DNS configuration: %v", err)
	}
	if parsed.DNS == nil || len(parsed.DNS.Servers) != 10 || len(parsed.DNS.Rules) != 3 {
		t.Fatalf("decoded DNS options do not contain all migrated server/rule entries: %#v", parsed.DNS)
	}
	if parsed.DNS.Servers[3].Type != C.DNSTypeFakeIP {
		t.Fatalf("expected FakeIP server at index 3, got %q", parsed.DNS.Servers[3].Type)
	}
}

func TestSingBox1143RejectsLegacyDNSFormats(t *testing.T) {
	t.Parallel()

	ctx := service.ContextWith[option.DNSTransportOptionsRegistry](context.Background(), dnsConfigTestRegistry{})
	for _, config := range []string{
		`{"dns":{"fakeip":{"enabled":true,"inet4_range":"198.18.0.0/15"}}}`,
		`{"dns":{"servers":[{"address":"https://dns.google/dns-query"}]}}`,
		`{"dns":{"servers":[{"type":"https","server":"dns.google","strategy":"ipv4_only"}]}}`,
	} {
		var parsed option.Options
		if err := parsed.UnmarshalJSONContext(ctx, []byte(config)); err == nil {
			t.Fatalf("sing-box 1.14.3 unexpectedly accepted legacy DNS config: %s", config)
		}
	}
}
