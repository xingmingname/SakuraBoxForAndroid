package sudoku

import (
	"context"
	"net"
	"os"
	"strconv"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	sudokut "libcore/sudoku/transport"
	"libcore/sudoku/transport/connutil"
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[OutboundOptions](registry, Type, NewOutbound)
}

// Outbound must stay a Lifecycle: the manager starts only components that implement it,
// so a stale Start signature would silently skip the mux maintainer.
var _ adapter.Lifecycle = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	ctx          context.Context
	logger       log.ContextLogger
	dialer       N.Dialer
	baseConf     sudokut.ProtocolConfig
	httpMaskPool *sudokut.HTTPMaskTransportPool
	muxClient    *sudokut.MuxClient
	muxCancel    context.CancelFunc
	muxDone      chan struct{}
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options OutboundOptions) (adapter.Outbound, error) {
	options = options.resolved()
	if options.Server == "" {
		return nil, E.New("missing server")
	}
	if options.ServerPort == 0 {
		return nil, E.New("missing server_port")
	}
	if options.Key == "" {
		return nil, E.New("missing key")
	}
	if _, err := TableCount(options); err != nil {
		return nil, err
	}

	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}

	defaultConf := sudokut.DefaultConfig()
	paddingMin, paddingMax := resolvePadding(defaultConf.PaddingMin, defaultConf.PaddingMax, options.PaddingMin, options.PaddingMax)
	multiplex := resolveMultiplex(defaultConf.Multiplex, options.Multiplex, options.HTTPMaskMultiplex)

	baseConf := sudokut.ProtocolConfig{
		ServerAddress:      net.JoinHostPort(options.Server, strconv.Itoa(int(options.ServerPort))),
		Key:                options.Key,
		AEADMethod:         defaultConf.AEADMethod,
		PaddingMin:         paddingMin,
		PaddingMax:         paddingMax,
		EnablePureDownlink: resolveBool(defaultConf.EnablePureDownlink, options.EnablePureDownlink),
		Multiplex:          multiplex,
		DisableHTTPMask:    options.DisableHTTPMask,
		HTTPMaskMode:       resolveHTTPMaskMode(defaultConf.HTTPMaskMode, options.HTTPMaskMode, options.HTTPMaskStrategy),
		HTTPMaskTLSEnabled: options.HTTPMaskTLS,
		HTTPMaskMultiplex:  options.HTTPMaskMultiplex,
		HTTPMaskHost:       options.HTTPMaskHost,
		HTTPMaskPathRoot:   options.HTTPMaskPathRoot,
	}
	if options.AEADMethod != "" {
		baseConf.AEADMethod = options.AEADMethod
	}

	budget, err := ResourceBudget(options)
	if err != nil {
		return nil, err
	}
	tables, err := buildProtocolTables(baseConf.Key, options.ASCII, options.CustomTable, options.CustomTables, true, budget)
	if err != nil {
		return nil, E.Cause(err, "build table(s)")
	}
	applyProtocolTables(&baseConf, tables)
	if err := baseConf.Validate(); err != nil {
		return nil, err
	}

	out := &Outbound{
		Adapter:      outbound.NewAdapterWithDialerOptions(Type, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		ctx:          ctx,
		logger:       logger,
		dialer:       outboundDialer,
		baseConf:     baseConf,
		httpMaskPool: new(sudokut.HTTPMaskTransportPool),
	}
	out.baseConf.DialContext = out.dialContext
	out.baseConf.HTTPMaskTransportPool = out.httpMaskPool

	if allowSessionMux(&out.baseConf) {
		out.muxClient, err = sudokut.NewMuxClient(&out.baseConf)
		if err != nil {
			return nil, err
		}
	}

	return out, nil
}

func (h *Outbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	// Bind background work and in-flight handshakes to the core lifecycle.
	h.ctx = scope.Context()
	scope.Add(h.Close)
	if h.muxClient != nil {
		muxCtx, cancel := context.WithCancel(h.ctx)
		h.muxCancel = cancel
		h.muxDone = make(chan struct{})
		go h.maintainMux(muxCtx)
	}
	return nil
}

func (h *Outbound) dialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	ctx, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(h.ctx, cancel)
	defer stop()
	defer cancel()
	return h.dialer.DialContext(ctx, network, M.ParseSocksaddr(addr))
}

func (h *Outbound) Close() error {
	if h.muxCancel != nil {
		h.muxCancel()
	}
	err := common.Close(common.PtrOrNil(h.muxClient))
	if h.muxDone != nil {
		<-h.muxDone
	}
	return E.Errors(err, common.Close(common.PtrOrNil(h.httpMaskPool)))
}

func (h *Outbound) maintainMux(ctx context.Context) {
	defer close(h.muxDone)
	ready := false
	failureReported := false
	h.muxClient.Maintain(ctx, func(err error) {
		if err == nil {
			if !ready {
				h.logger.InfoContext(h.ctx, "Sudoku mux warm session ready")
			}
			ready = true
			failureReported = false
			return
		}
		if !failureReported {
			h.logger.WarnContext(h.ctx, E.Cause(err, "Sudoku mux warm session unavailable"))
		}
		ready = false
		failureReported = true
	})
}

func (h *Outbound) connectionContext(ctx context.Context) (context.Context, func()) {
	ctx, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(h.ctx, cancel)
	return ctx, func() { stop(); cancel() }
}

type lifecycleConn struct {
	net.Conn
	release func()
}

func (c *lifecycleConn) Close() error      { c.release(); return c.Conn.Close() }
func (c *lifecycleConn) CloseRead() error  { return connutil.TryCloseRead(c.Conn) }
func (c *lifecycleConn) CloseWrite() error { return connutil.TryCloseWrite(c.Conn) }

func (c *lifecycleConn) EnableStreamCancellation() {
	if stream, ok := c.Conn.(interface{ EnableStreamCancellation() }); ok {
		stream.EnableStreamCancellation()
	}
}

func (c *lifecycleConn) Abort() error {
	c.release()
	return abortStream(c.Conn)
}

func abortStream(conn net.Conn) error {
	if stream, ok := conn.(interface{ Abort() error }); ok {
		return stream.Abort()
	}
	return conn.Close()
}

func bindConnection(ctx context.Context, conn net.Conn, release func()) net.Conn {
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	return &lifecycleConn{Conn: conn, release: func() { stop(); release() }}
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, release := h.connectionContext(ctx)
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination

	var conn net.Conn
	var err error
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		metadata.Network = N.NetworkTCP
		if h.muxClient != nil {
			conn, err = h.muxClient.Dial(ctx, destination.String())
		} else {
			cfg := h.baseConf
			cfg.TargetAddress = destination.String()
			conn, err = sudokut.Dial(ctx, &cfg)
		}
	case N.NetworkUDP:
		err = E.New("UDP dial is not supported, use listen_packet instead")
	default:
		err = os.ErrInvalid
	}
	if err != nil {
		release()
		return nil, err
	}
	return bindConnection(ctx, conn, release), nil
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, release := h.connectionContext(ctx)
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	metadata.Network = N.NetworkUDP

	cfg := h.baseConf
	conn, err := sudokut.DialUDPOverTCP(ctx, &cfg)
	if err != nil {
		release()
		return nil, err
	}
	return sudokut.NewUoTPacketConn(bindConnection(ctx, conn, release)), nil
}
