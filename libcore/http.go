package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/ech"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

// Full in-memory content is used by subscriptions/configuration. Asset writes
// remain streaming and have a separate, much larger disk quota.
const maxHTTPContentBytes int64 = 32 << 20
const maxHTTPFileBytes int64 = 512 << 20

type HTTPClient interface {
	SetTimeout(milliseconds int32)
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
	timeout       time.Duration
}

func NewHttpClient() HTTPClient {
	client := &httpClient{timeout: 30 * time.Second}
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	return client
}

func (c *httpClient) SetTimeout(milliseconds int32) {
	if milliseconds > 0 {
		c.timeout = time.Duration(milliseconds) * time.Millisecond
	}
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) TrySocks5(port int32) {
	dialer := new(net.Dialer)
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			if deadline, ok := ctx.Deadline(); ok {
				_ = socksConn.SetDeadline(deadline)
			}
			stopCancel := context.AfterFunc(ctx, func() { _ = socksConn.Close() })
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
			stopCancel()
			if ctx.Err() != nil {
				socksConn.Close()
				return nil, ctx.Err()
			}
			if err != nil {
				socksConn.Close()
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			_ = socksConn.SetDeadline(time.Time{})
			return socksConn, err
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request       http.Request
	allowInsecure bool
}

func (r *httpRequest) AllowInsecure() {
	r.allowInsecure = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
		r.request.URL.User = nil
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	buffer := bytes.Buffer{}
	buffer.Write(content)
	r.request.Body = io.NopCloser(bytes.NewReader(buffer.Bytes()))
	r.request.ContentLength = int64(len(content))
	r.request.GetBody = func() (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(buffer.Bytes())), nil }
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (result HTTPResponse, err error) {
	var cancel context.CancelFunc
	defer func() {
		if value := recover(); value != nil {
			if cancel != nil {
				cancel()
			}
			result = nil
			err = fmt.Errorf("HTTP request panic: %v", value)
		}
	}()
	if r.request.URL == nil || (r.request.URL.Scheme != "http" && r.request.URL.Scheme != "https") {
		return nil, errors.New("HTTP URL must use http or https")
	}
	ctx, cancelRequest := context.WithTimeout(r.request.Context(), r.timeout)
	cancel = cancelRequest
	request := r.request.Clone(ctx)
	client := &r.h1h2Client
	var cleanup func()
	if r.allowInsecure {
		transport := r.h1h2Transport.Clone()
		transport.TLSClientConfig = r.tls.Clone()
		transport.TLSClientConfig.InsecureSkipVerify = true
		client = &http.Client{Transport: transport}
		cleanup = transport.CloseIdleConnections
	}
	if r.tryH3Direct && !r.trySocks5 {
		result, err = r.doH3Direct(ctx)
	} else {
		var response *http.Response
		response, err = client.Do(request)
		if err != nil && r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			result, err = r.doH3Direct(ctx)
		} else if err == nil {
			result, err = checkedHTTPResponse(response)
		}
	}
	if err != nil || result == nil {
		cancel()
		if cleanup != nil {
			cleanup()
		}
		if err == nil {
			err = errors.New("HTTP request produced no response")
		}
		return nil, err
	}
	response := result.(*httpResponse)
	response.Body = &cleanupBody{ReadCloser: response.Body, cleanup: func() {
		cancel()
		if cleanup != nil {
			cleanup()
		}
	}}
	return response, nil
}
func checkedHTTPResponse(response *http.Response) (HTTPResponse, error) {
	if response == nil {
		return nil, errors.New("HTTP transport produced no response")
	}
	result := &httpResponse{Response: response}
	if response.StatusCode != http.StatusOK {
		return nil, errors.New(result.errorString())
	}
	return result, nil
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

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		if h.ContentLength > maxHTTPContentBytes {
			h.contentError = errors.New("HTTP response exceeds 32 MiB in-memory limit")
			return
		}
		h.content, h.contentError = io.ReadAll(io.LimitReader(h.Body, maxHTTPContentBytes+1))
		if h.contentError == nil && int64(len(h.content)) > maxHTTPContentBytes {
			h.contentError = errors.New("HTTP response exceeds 32 MiB in-memory limit")
		}
		if h.contentError != nil {
			h.content = nil
		}
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	defer h.Body.Close()
	if h.ContentLength > maxHTTPFileBytes {
		return errors.New("HTTP asset exceeds 512 MiB download limit")
	}
	// A failed or over-quota update must not truncate the existing asset.
	file, err := os.CreateTemp(filepath.Dir(path), ".http-download-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	n, err := io.Copy(file, io.LimitReader(h.Body, maxHTTPFileBytes+1))
	if err != nil {
		return err
	}
	if n > maxHTTPFileBytes {
		return errors.New("HTTP asset exceeds 512 MiB download limit")
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}
