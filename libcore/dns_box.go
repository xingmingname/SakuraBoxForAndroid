// libbox/dns.go

package libcore

import (
	"context"
	"net/netip"
	"strings"
	"sync"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/task"

	mDNS "github.com/miekg/dns"
)

var rawQueryFunc func(networkHandle int64, request []byte) ([]byte, error)

type LocalDNSTransport interface {
	Raw() bool
	NetworkHandle() int64
	Lookup(ctx *ExchangeContext, network string, domain string) error
	Exchange(ctx *ExchangeContext, message []byte) error
	// DNS servers provisioned by Android on the current underlying network.
	// A non-empty interface must match that network; never silently switch it.
	DNSServers(interfaceName string) (string, error)
}

var gLocalDNSTransport *platformLocalDNSTransport = nil

type platformLocalDNSTransport struct {
	dns.TransportAdapter
	iif LocalDNSTransport
	raw bool
}

func newPlatformTransport(iif LocalDNSTransport, tag string, options option.LocalDNSServerOptions) *platformLocalDNSTransport {
	return &platformLocalDNSTransport{
		TransportAdapter: dns.NewTransportAdapterWithLocalOptions(constant.DNSTypeLocal, tag, options),
		iif:              iif,
		raw:              iif.Raw(),
	}
}

func (p *platformLocalDNSTransport) Start(stage adapter.StartStage, _ *adapter.Scope) error {
	return nil
}

func (p *platformLocalDNSTransport) Close() error {
	return nil
}

func (p *platformLocalDNSTransport) Reset() {}

func (p *platformLocalDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	// Only the test wrapper implements this guard. Normal VPN DNS is unchanged.
	if pinned, ok := p.iif.(interface{ CheckNetwork() error }); ok {
		if err := pinned.CheckNetwork(); err != nil {
			return nil, err
		}
	}
	if len(message.Question) != 1 {
		return nil, E.New("exactly one DNS question is required")
	}
	if p.raw && rawQueryFunc != nil {
		// Raw - Android 10 及以上才有

		messageBytes, err := message.Pack()
		if err != nil {
			return nil, err
		}
		msg, err := rawQueryFunc(p.iif.NetworkHandle(), messageBytes)
		if err != nil {
			return nil, err
		}
		responseMessage := new(mDNS.Msg)
		err = responseMessage.Unpack(msg)
		if err != nil {
			return nil, err
		}
		return responseMessage, nil
	} else {
		// Lookup - Android 10 以下

		question := message.Question[0]
		var network string
		switch question.Qtype {
		case mDNS.TypeA:
			network = "ip4"
		case mDNS.TypeAAAA:
			network = "ip6"
		default:
			return nil, E.New("only IP queries are supported by current version of Android")
		}

		response := newExchangeContext(ctx)

		var responseAddrs []netip.Addr
		var group task.Group
		group.Append0(func(ctx context.Context) error {
			err := p.iif.Lookup(response, network, question.Name)
			if err != nil {
				return err
			}
			select {
			case <-response.finished:
			case <-ctx.Done():
				return context.Canceled
			}
			if response.error != nil {
				return response.error
			}
			responseAddrs = response.addresses
			return nil
		})
		err := group.Run(ctx)
		if err != nil {
			return nil, err
		}
		return dns.FixedResponse(message.Id, question, responseAddrs, constant.DefaultDNSTTL), nil
	}
}

func (p *platformLocalDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	go func() {
		response, err := p.Exchange(ctx, message)
		callback(response, err)
	}()
}

type Func interface {
	Invoke() error
}

type ExchangeContext struct {
	context      context.Context
	message      mDNS.Msg
	addresses    []netip.Addr
	error        error
	finished     chan struct{}
	completeOnce sync.Once
}

func newExchangeContext(ctx context.Context) *ExchangeContext {
	return &ExchangeContext{context: ctx, finished: make(chan struct{})}
}

// Both the result and the completion signal belong to the first callback.
// Later success/error/cancellation callbacks cannot mutate a published result.
func (c *ExchangeContext) complete(update func()) (won bool) {
	c.completeOnce.Do(func() {
		update()
		close(c.finished)
		won = true
	})
	return
}

func (c *ExchangeContext) OnCancel(callback Func) {
	go func() {
		select {
		case <-c.finished:
		case <-c.context.Done():
			if c.complete(func() { c.error = c.context.Err() }) {
				callback.Invoke()
			}
		}
	}()
}

func (c *ExchangeContext) Success(result string) {
	c.complete(func() {
		for _, value := range strings.Split(result, "\n") {
			value = strings.TrimSpace(value)
			if value == "" {
				continue
			}
			address, err := netip.ParseAddr(value)
			if err != nil {
				c.error = E.Cause(err, "invalid DNS lookup address")
				return
			}
			c.addresses = append(c.addresses, address)
		}
	})
}

func (c *ExchangeContext) RawSuccess(result []byte) {
	c.complete(func() {
		if err := c.message.Unpack(result); err != nil {
			c.error = E.Cause(err, "parse response")
		}
	})
}

func (c *ExchangeContext) ErrorCode(code int32) {
	c.complete(func() { c.error = dns.RcodeError(code) })
}

func (c *ExchangeContext) ErrnoCode(code int32) {
	c.complete(func() { c.error = syscall.Errno(code) })
}
