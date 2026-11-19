package nakis

import (
	"context"
	"errors"
	"net"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/bufio"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

var errClosed = errors.New("nakis: outbound closed")

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[OutboundOptions](registry, Type, NewOutbound)
}

type Outbound struct {
	outbound.Adapter
	cfg        clientConfig
	dialer     N.Dialer
	tlsContext context.Context

	mu     sync.Mutex
	ctx    context.Context
	pool   *tunnelPool
	closed bool
}

var _ adapter.Lifecycle = (*Outbound)(nil)
var _ adapter.InterfaceUpdateListener = (*Outbound)(nil)

func NewOutbound(ctx context.Context, _ adapter.Router, logger log.ContextLogger, tag string, options OutboundOptions) (adapter.Outbound, error) {
	cfg, err := resolveOptions(options)
	if err != nil {
		return nil, err
	}
	dialOptions := options.DialerOptions
	if dialOptions.TCPKeepAlive == 0 {
		dialOptions.TCPKeepAlive = badoption.Duration(cfg.keepalive)
	}
	if dialOptions.TCPKeepAliveInterval == 0 {
		dialOptions.TCPKeepAliveInterval = badoption.Duration(cfg.keepalive)
	}
	remoteIsDomain := false
	for _, server := range cfg.servers {
		remoteIsDomain = remoteIsDomain || server.IsDomain()
	}
	d, err := dialer.New(ctx, dialOptions, remoteIsDomain)
	if err != nil {
		return nil, err
	}
	if options.Congestion != "" && dialOptions.Detour == "" && setCongestion != nil {
		d = congestionDialer{Dialer: d, name: options.Congestion, logger: logger}
	}
	return &Outbound{
		Adapter: outbound.NewAdapterWithDialerOptions(Type, tag, []string{N.NetworkTCP, N.NetworkUDP}, dialOptions),
		cfg:     cfg, dialer: d, tlsContext: ctx,
	}, nil
}

func (o *Outbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.closed {
		return errClosed
	}
	if o.pool == nil {
		o.ctx = scope.Context()
		o.pool = newPool(o.ctx, o.cfg, o.dialer, clientTLS(o.tlsContext, o.cfg))
		scope.Add(o.Close)
	}
	return nil
}

func (o *Outbound) Close() error {
	o.mu.Lock()
	o.closed = true
	if o.pool != nil {
		o.pool.close()
		o.pool = nil
	}
	o.mu.Unlock()
	return nil
}

func (o *Outbound) InterfaceUpdated(_ context.Context) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.closed || o.pool == nil {
		return
	}
	o.pool.close()
	o.pool = newPool(o.ctx, o.cfg, o.dialer, clientTLS(o.tlsContext, o.cfg))
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		pool := o.currentPool()
		if pool == nil {
			return nil, errClosed
		}
		return pool.dial(ctx, destination)
	case N.NetworkUDP:
		pc, err := o.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(pc, destination), nil
	default:
		return nil, net.UnknownNetworkError(network)
	}
}

func (o *Outbound) ListenPacket(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	pool := o.currentPool()
	if pool == nil {
		return nil, errClosed
	}
	return pool.listenPacket(ctx)
}

func (o *Outbound) currentPool() *tunnelPool {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.pool
}
