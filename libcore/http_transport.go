package libcore

import (
	"context"
	"errors"
	"libcore/ech"
	"net"
	"net/http"
	"strconv"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

var errRequiredSocks5 = errors.New("Required SOCKS5 proxy is unavailable or rejected the connection")

func (c *httpClient) TrySocks5(port int32) {
	dialer := new(net.Dialer)
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		if c.requireSocks5 {
			var cancel context.CancelFunc
			ctx, cancel = context.WithTimeout(ctx, c.timeout)
			defer cancel()
		}
		conn, err := dialSOCKS5(ctx, dialer, port, addr)
		if err == nil {
			return conn, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if c.requireSocks5 {
			if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
				return nil, context.DeadlineExceeded
			}
			return nil, errFailConnectSocks5
		}
		if c.tryH3Direct {
			return nil, errFailConnectSocks5
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func dialSOCKS5(ctx context.Context, dialer *net.Dialer, port int32, addr string) (net.Conn, error) {
	if port <= 0 || port > 65535 {
		return nil, errFailConnectSocks5
	}
	conn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
	if err != nil {
		return nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	stopCancel := context.AfterFunc(ctx, func() { _ = conn.Close() })
	_, err = socks.ClientHandshake5(conn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
	stopCancel()
	if err != nil || ctx.Err() != nil {
		conn.Close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (r *httpRequest) doH3Direct(ctx context.Context) (HTTPResponse, error) {
	tlsConfig := r.tls.Clone()
	tlsConfig.InsecureSkipVerify = r.allowInsecure
	attempts := []httpAttempt{
		func(ctx context.Context) (*http.Response, error, func()) {
			transport := &http.Transport{DisableKeepAlives: true, DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				var d net.Dialer
				conn, err := d.DialContext(ctx, network, addr)
				if err != nil {
					return nil, err
				}
				domain, _, err := net.SplitHostPort(addr)
				if err != nil {
					domain = addr
				}
				config := ech.NewECHClientConfig(domain, tlsConfig, gLocalDNSTransport)
				tlsConn, err := config.Client(ctx, conn)
				if err != nil {
					conn.Close()
					return nil, err
				}
				if err = tlsConn.HandshakeContext(ctx); err != nil {
					tlsConn.Close()
					return nil, err
				}
				return tlsConn, nil
			}}
			request, err := r.cloneAttempt(ctx)
			if err != nil {
				return nil, err, transport.CloseIdleConnections
			}
			response, err := (&http.Client{Transport: transport}).Do(request)
			return response, err, transport.CloseIdleConnections
		},
		func(ctx context.Context) (*http.Response, error, func()) {
			transport := &http3.Transport{TLSClientConfig: tlsConfig.Clone(), QUICConfig: &quic.Config{MaxIdleTimeout: time.Second}}
			cleanup := func() { _ = transport.Close() }
			request, err := r.cloneAttempt(ctx)
			if err != nil {
				return nil, err, cleanup
			}
			response, err := (&http.Client{Transport: transport}).Do(request)
			return response, err, cleanup
		},
	}
	if r.request.URL.Scheme == "http" {
		attempts = attempts[:1]
	}
	response, err := raceHTTPAttempts(ctx, attempts)
	if err != nil {
		return nil, err
	}
	return checkedHTTPResponse(response)
}
func (r *httpRequest) cloneAttempt(ctx context.Context) (*http.Request, error) {
	request := r.request.Clone(ctx)
	if r.request.Body != nil {
		if r.request.GetBody == nil {
			return nil, errors.New("HTTP request body cannot be replayed")
		}
		body, err := r.request.GetBody()
		if err != nil {
			return nil, err
		}
		request.Body = body
	}
	return request, nil
}
