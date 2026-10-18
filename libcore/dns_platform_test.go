package libcore

import (
	"context"
	"errors"
	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"net"
	"sync"
	"testing"
	"time"
)

type testDNSProvider struct {
	lookup    func(*ExchangeContext)
	servers   string
	serverErr error
}

func TestDHCPUsesCoreUDPTransport(t *testing.T) {
	connection, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &mDNS.Server{PacketConn: connection, Handler: mDNS.HandlerFunc(func(w mDNS.ResponseWriter, request *mDNS.Msg) {
		answer := new(mDNS.Msg)
		answer.SetReply(request)
		answer.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: request.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 30}, A: net.IPv4(192, 0, 2, 8)}}
		_ = w.WriteMsg(answer)
	})}
	go server.ActivateAndServe()
	defer server.Shutdown()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	instance, err := newPlatformDHCPTransport(ctx, log.NewNOPFactory().Logger(), "dns", option.DHCPDNSServerOptions{}, testDNSProvider{})
	if err != nil {
		t.Fatal(err)
	}
	msg := new(mDNS.Msg)
	msg.SetQuestion("example.invalid.", mDNS.TypeA)
	answer, err := instance.(*platformDHCPTransport).exchangeServer(ctx, msg, M.ParseSocksaddr(connection.LocalAddr().String()))
	if err != nil || answer == nil || len(answer.Answer) != 1 {
		t.Fatalf("core UDP DNS exchange failed: %v %v", answer, err)
	}
}

func (p testDNSProvider) Raw() bool            { return false }
func (p testDNSProvider) NetworkHandle() int64 { return 1 }
func (p testDNSProvider) Lookup(c *ExchangeContext, network, domain string) error {
	p.lookup(c)
	return nil
}
func (p testDNSProvider) Exchange(c *ExchangeContext, message []byte) error { return nil }
func (p testDNSProvider) DNSServers(interfaceName string) (string, error) {
	return p.servers, p.serverErr
}

func TestDNSSuccessCompletesImmediately(t *testing.T) {
	for _, query := range []uint16{mDNS.TypeA, mDNS.TypeAAAA} {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		address := "192.0.2.1"
		if query == mDNS.TypeAAAA {
			address = "2001:db8::1"
		}
		provider := testDNSProvider{lookup: func(c *ExchangeContext) { c.Success(address); c.ErrnoCode(5); c.Success("192.0.2.9") }}
		msg := new(mDNS.Msg)
		msg.SetQuestion("example.invalid.", query)
		response, err := newPlatformTransport(provider, "local", option.LocalDNSServerOptions{}).Exchange(ctx, msg)
		cancel()
		if err != nil || response == nil || len(response.Answer) != 1 {
			t.Fatalf("successful callback failed: %v %v", response, err)
		}
	}
}

func TestDNSCallbackRaces(t *testing.T) {
	for i := 0; i < 100; i++ {
		c := newExchangeContext(context.Background())
		var wg sync.WaitGroup
		for j := 0; j < 10; j++ {
			wg.Add(1)
			go func() { defer wg.Done(); c.Success("192.0.2.1") }()
		}
		wg.Wait()
		<-c.finished
		if len(c.addresses) != 1 {
			t.Fatal("duplicate callback changed result")
		}
	}
	c := newExchangeContext(context.Background())
	c.Success("invalid-address")
	<-c.finished
	if c.error == nil {
		t.Fatal("invalid address accepted")
	}
	c = newExchangeContext(context.Background())
	c.Success("")
	<-c.finished
	if c.error != nil || len(c.addresses) != 0 {
		t.Fatal("empty successful response changed")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	msg := new(mDNS.Msg)
	msg.SetQuestion("example.invalid.", mDNS.TypeA)
	cancel()
	_, err := newPlatformTransport(testDNSProvider{lookup: func(*ExchangeContext) {}}, "local", option.LocalDNSServerOptions{}).Exchange(ctx, msg)
	if err == nil {
		t.Fatal("cancelled DNS request succeeded")
	}
}

func TestDHCPProductionRegistryAndErrors(t *testing.T) {
	provider := testDNSProvider{serverErr: errors.New("interface is not underlying network")}
	registry := nekoboxAndroidDNSTransportRegistry(provider)
	ctx := service.ContextWith[option.DNSTransportOptionsRegistry](context.Background(), registry)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(`{"dns":{"servers":[{"type":"dhcp","tag":"dns","interface":"wlan0"}]}}`)); err != nil {
		t.Fatal(err)
	}
	transport, err := registry.CreateDNSTransport(ctx, log.NewNOPFactory().Logger(), "dns", "dhcp", options.DNS.Servers[0].Options)
	if err != nil {
		t.Fatal(err)
	}
	msg := new(mDNS.Msg)
	msg.SetQuestion("example.invalid.", mDNS.TypeA)
	if _, err = transport.Exchange(ctx, msg); !errors.Is(err, provider.serverErr) {
		t.Fatalf("interface failure hidden: %v", err)
	}
	for _, text := range []string{"", "not-an-ip", "0.0.0.0", "ff02::1"} {
		if _, err := provisionedDNSServers(text); err == nil {
			t.Fatalf("bad DNS accepted: %q", text)
		}
	}
	servers, err := provisionedDNSServers("192.0.2.53\n2001:db8::53\nfe80::1%wlan0")
	if err != nil || len(servers) != 3 {
		t.Fatalf("IPv4/IPv6 DNS lost: %v", err)
	}
}
