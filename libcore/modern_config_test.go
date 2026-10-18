package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	mdns "github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	R "github.com/sagernet/sing-box/route/rule"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

func modernTestCore(t *testing.T, text []byte) (*box.Box, context.Context) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	data, _, err := prepareConfig(string(text))
	if err != nil {
		t.Fatal(err)
	}
	var opts option.Options
	if err = opts.UnmarshalJSONContext(ctx, data); err != nil {
		t.Fatal(err)
	}
	b, err := box.New(box.Options{Options: opts, Context: ctx})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	return b, ctx
}

func TestModernProductionFamilyQueries(t *testing.T) {
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Skip("JVM production fixtures not provided")
	}
	socket, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &mdns.Server{PacketConn: socket, Handler: mdns.HandlerFunc(func(w mdns.ResponseWriter, q *mdns.Msg) {
		m := new(mdns.Msg)
		m.SetReply(q)
		record := ""
		if q.Question[0].Qtype == mdns.TypeA {
			record = "192.0.2.1"
		} else if q.Question[0].Qtype == mdns.TypeAAAA {
			record = "2001:db8::1"
		}
		if record != "" {
			rr, _ := mdns.NewRR(fmt.Sprintf("%s 30 IN %s %s", q.Question[0].Name, mdns.TypeToString[q.Question[0].Qtype], record))
			m.Answer = []mdns.RR{rr}
		}
		w.WriteMsg(m)
	})}
	go server.ActivateAndServe()
	defer server.Shutdown()
	for _, policy := range []string{"ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6", "as_is"} {
		t.Run(policy, func(t *testing.T) {
			data, err := os.ReadFile(filepath.Join(dir, "modern-family-"+policy+".json"))
			if err != nil {
				t.Fatal(err)
			}
			// Only the ephemeral loopback test server port is substituted.
			var cfg map[string]any
			if err = json.Unmarshal(data, &cfg); err != nil {
				t.Fatal(err)
			}
			dns := cfg["dns"].(map[string]any)
			for _, v := range dns["servers"].([]any) {
				s := v.(map[string]any)
				if s["type"] == "udp" {
					s["server_port"] = socket.LocalAddr().(*net.UDPAddr).Port
				}
			}
			data, _ = json.Marshal(cfg)
			b, ctx := modernTestCore(t, data)
			if err = b.Start(); err != nil {
				t.Fatal(err)
			}
			router := service.FromContext[adapter.DNSRouter](ctx)
			for _, qt := range []uint16{mdns.TypeA, mdns.TypeAAAA} {
				q := new(mdns.Msg)
				q.SetQuestion("family.example.invalid.", qt)
				c, cancel := context.WithTimeout(ctx, 5*time.Second)
				response, e := router.Exchange(c, q, adapter.DNSQueryOptions{})
				cancel()
				if e != nil {
					t.Fatal(e)
				}
				denied := policy == "ipv4_only" && qt == mdns.TypeAAAA || policy == "ipv6_only" && qt == mdns.TypeA
				if (len(response.Answer) == 0) != denied || response.Rcode != mdns.RcodeSuccess {
					t.Fatalf("family policy %s qtype=%d answers=%d", policy, qt, len(response.Answer))
				}
			}
			if !strings.HasSuffix(policy, "_only") {
				addrs, e := router.Lookup(ctx, "family.example.invalid", adapter.DNSQueryOptions{})
				if e != nil {
					t.Fatal(e)
				}
				if len(addrs) != 2 || policy == "prefer_ipv6" && !addrs[0].Is6() || policy == "prefer_ipv4" && !addrs[0].Is4() {
					t.Fatalf("lookup order lost: %v", addrs)
				}
			}
		})
	}
}

func TestModernWireGuardDefaultManager(t *testing.T) {
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Skip("JVM production fixtures not provided")
	}
	for _, variant := range []string{"default", "override"} {
		t.Run(variant, func(t *testing.T) {
			data, err := os.ReadFile(filepath.Join(dir, "modern-wireguard-"+variant+".json"))
			if err != nil {
				t.Fatal(err)
			}
			b, ctx := modernTestCore(t, data)
			// Initialize the real manager, including endpoint fallback. No WG peer packets.
			scope := adapter.NewScope(ctx, log.NewNOPFactory().Logger())
			defer scope.Close()
			if err = b.Outbound().Start(adapter.StartStateInitialize, scope); err != nil {
				t.Fatal(err)
			}
			want := "proxy"
			if variant == "override" {
				want = "bypass"
			}
			outbound := b.Outbound().Default()
			if outbound.Tag() != want {
				t.Fatalf("default %q, want %q", outbound.Tag(), want)
			}
			if variant == "default" {
				if outbound.Type() != "wireguard" {
					t.Fatal("not wireguard")
				}
				for _, n := range []string{"tcp", "udp"} {
					found := false
					for _, v := range outbound.Network() {
						found = found || v == n
					}
					if !found {
						t.Fatalf("missing %s", n)
					}
				}
			}
		})
	}
}

func TestModernProductionFakeIPQueries(t *testing.T) {
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Skip("JVM fixtures not provided")
	}
	for _, only6 := range []bool{false, true} {
		t.Run(fmt.Sprint(only6), func(t *testing.T) {
			data, err := os.ReadFile(filepath.Join(dir, fmt.Sprintf("modern-fakeip-%v.json", only6)))
			if err != nil {
				t.Fatal(err)
			}
			b, ctx := modernTestCore(t, data)
			if err = b.Start(); err != nil {
				t.Fatal(err)
			}
			router := service.FromContext[adapter.DNSRouter](ctx)
			for _, domain := range []string{"test.invalid.", "uid.invalid.", "default.invalid."} {
				for _, qt := range []uint16{mdns.TypeA, mdns.TypeAAAA} {
					c, metadata := adapter.ExtendContext(ctx)
					metadata.Inbound = "tun-in"
					if domain == "uid.invalid." {
						metadata.ProcessInfo = &adapter.ConnectionOwner{UserId: 10001}
					}
					q := new(mdns.Msg)
					q.SetQuestion(domain, qt)
					reply, e := router.Exchange(c, q, adapter.DNSQueryOptions{})
					if e != nil {
						t.Fatal(e)
					}
					denied := only6 && qt == mdns.TypeA || !only6 && qt == mdns.TypeAAAA
					if (len(reply.Answer) == 0) != denied || reply.Rcode != mdns.RcodeSuccess {
						t.Fatalf("FakeIP family mismatch only6=%v qtype=%d", only6, qt)
					}
				}
			}
		})
	}
}

func TestLegacyDNSModeConflictIsStillRejectedByUnmodifiedCore(t *testing.T) {
	config := `{"dns":{"servers":[{"type":"local","tag":"dns"}],"rules":[{"domain":["legacy.invalid"],"server":"dns","strategy":"prefer_ipv4"},{"query_type":["TXT"],"server":"dns"}]},"outbounds":[{"type":"direct","tag":"direct"}]}`
	if err := startCoreConfig(config, false); err == nil || !strings.Contains(err.Error(), "strategy") {
		t.Fatalf("old conflict was hidden: %v", err)
	}
}

func TestModernProductionInboundLookupPreferences(t *testing.T) {
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Skip("JVM fixtures not provided")
	}
	socket, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &mdns.Server{PacketConn: socket, Handler: mdns.HandlerFunc(func(w mdns.ResponseWriter, q *mdns.Msg) {
		response := new(mdns.Msg)
		response.SetReply(q)
		address := "192.0.2.1"
		if q.Question[0].Qtype == mdns.TypeAAAA {
			address = "2001:db8::1"
		}
		rr, _ := mdns.NewRR(fmt.Sprintf("%s 30 IN %s %s", q.Question[0].Name, mdns.TypeToString[q.Question[0].Qtype], address))
		response.Answer = []mdns.RR{rr}
		w.WriteMsg(response)
	})}
	go server.ActivateAndServe()
	defer server.Shutdown()
	data, err := os.ReadFile(filepath.Join(dir, "modern-inbound-preferences.json"))
	if err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	json.Unmarshal(data, &root)
	for _, value := range root["dns"].(map[string]any)["servers"].([]any) {
		s := value.(map[string]any)
		if s["type"] == "udp" {
			s["server_port"] = socket.LocalAddr().(*net.UDPAddr).Port
		}
	}
	data, _ = json.Marshal(root)
	b, ctx := modernTestCore(t, data)
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	router := service.FromContext[adapter.DNSRouter](ctx)
	for _, domain := range []string{"family.example.invalid", "fallback.invalid"} {
		metadata := adapter.InboundContext{Inbound: "in", Domain: domain, Destination: M.ParseSocksaddrHostPort(domain, 443)}
		var action *R.RuleActionResolve
		matches := 0
		for _, rule := range b.Router().Rules() {
			if resolve, ok := rule.Action().(*R.RuleActionResolve); ok && rule.Match(&metadata) {
				action = resolve
				matches++
			}
		}
		if matches != 1 {
			t.Fatalf("resolve policy matched %d times", matches)
		}
		want := C.DomainStrategyPreferIPv4
		if domain == "family.example.invalid" {
			want = C.DomainStrategyPreferIPv6
		}
		if action.Strategy != want {
			t.Fatal("per-rule preference lost")
		}
		addrs, e := router.Lookup(adapter.WithContext(ctx, &metadata), domain, adapter.DNSQueryOptions{Strategy: action.Strategy})
		if e != nil || len(addrs) != 2 || (want == C.DomainStrategyPreferIPv6) != addrs[0].Is6() {
			t.Fatalf("actual lookup order changed: %v %v", addrs, e)
		}
	}
}
