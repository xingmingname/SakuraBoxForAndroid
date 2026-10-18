package libcore

import (
	"context"
	"fmt"
	"net/netip"
	"strings"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

// Android already performs DHCP. Query its provisioned servers rather than
// attempting a second, privileged DHCP exchange on UDP port 68.
type platformDHCPTransport struct {
	dns.TransportAdapter
	ctx      context.Context
	logger   log.ContextLogger
	provider LocalDNSTransport
	options  option.DHCPDNSServerOptions
}

func newPlatformDHCPTransport(ctx context.Context, logger log.ContextLogger, tag string, options option.DHCPDNSServerOptions, provider LocalDNSTransport) (adapter.DNSTransport, error) {
	return &platformDHCPTransport{dns.NewTransportAdapterWithLocalOptions(C.DNSTypeDHCP, tag, options.LocalDNSServerOptions), ctx, logger, provider, options}, nil
}

func (p *platformDHCPTransport) Start(adapter.StartStage, *adapter.Scope) error { return nil }
func (p *platformDHCPTransport) Reset()                                         {}

func provisionedDNSServers(value string) ([]netip.Addr, error) {
	var servers []netip.Addr
	for _, text := range strings.Fields(value) {
		address, err := netip.ParseAddr(text)
		if err != nil || address.IsUnspecified() || address.IsMulticast() {
			return nil, fmt.Errorf("invalid provisioned DNS address %q", text)
		}
		servers = append(servers, address)
	}
	if len(servers) == 0 {
		return nil, fmt.Errorf("underlying network has no provisioned DNS servers")
	}
	return servers, nil
}

func (p *platformDHCPTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	if p.provider == nil {
		return nil, fmt.Errorf("Android DNS provider unavailable")
	}
	value, err := p.provider.DNSServers(p.options.Interface)
	if err != nil {
		return nil, err
	}
	servers, err := provisionedDNSServers(value)
	if err != nil {
		return nil, err
	}
	// Each query owns its sockets; network switches cannot reuse stale sockets.
	for _, address := range servers {
		if err = ctx.Err(); err != nil {
			return nil, err
		}
		response, queryErr := p.exchangeServer(ctx, message, M.Socksaddr{Addr: address, Port: 53})
		err = queryErr
		if err == nil {
			return response, nil
		}
	}
	return nil, fmt.Errorf("all provisioned DNS servers failed: %w", err)
}

func (p *platformDHCPTransport) exchangeServer(ctx context.Context, message *mDNS.Msg, server M.Socksaddr) (*mDNS.Msg, error) {
	dialer, err := dns.NewLocalDialer(p.ctx, p.options.LocalDNSServerOptions)
	if err != nil {
		return nil, err
	}
	query := transport.NewUDPRaw(p.logger, p.TransportAdapter, dialer, server)
	scope := adapter.NewScope(ctx, p.logger)
	defer scope.Close()
	if err = query.Start(adapter.StartStateStart, scope); err != nil {
		return nil, err
	}
	return query.Exchange(scope.Context(), message.Copy())
}

func (p *platformDHCPTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	go func() { response, err := p.Exchange(ctx, message); callback(response, err) }()
}
