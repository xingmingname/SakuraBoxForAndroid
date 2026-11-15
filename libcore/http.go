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
	"net/http"
	"net/url"
	"time"
)

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
	requireSocks5 bool
	tryH3Direct   bool
	timeout       time.Duration
}

func NewHttpClient() HTTPClient {
	return newHTTPClient()
}

// NewSocks5OnlyHttpClient creates a client for requests that must never dial
// the origin directly, including when the proxy fails or the request redirects.
func NewSocks5OnlyHttpClient(port int32) HTTPClient {
	client := newHTTPClient()
	client.requireSocks5 = true
	client.TrySocks5(port)
	return client
}

func newHTTPClient() *httpClient {
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
		if r.requireSocks5 {
			return errors.New("Invalid HTTP URL for SOCKS5 proxy request")
		}
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
			if r.requireSocks5 {
				err = errors.New("Request through SOCKS5 proxy failed")
			} else {
				err = fmt.Errorf("HTTP request panic: %v", value)
			}
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
	allowH3Direct := r.tryH3Direct && !r.requireSocks5
	if allowH3Direct && !r.trySocks5 {
		result, err = r.doH3Direct(ctx)
	} else {
		var response *http.Response
		response, err = client.Do(request)
		switch {
		case err != nil && allowH3Direct && errors.Is(err, errFailConnectSocks5):
			result, err = r.doH3Direct(ctx)
		case err != nil && r.requireSocks5:
			// net/http wraps failures in a URL error that may include a subscription token.
			switch {
			case errors.Is(err, context.DeadlineExceeded):
				err = errors.New("Request through SOCKS5 proxy timed out")
			case errors.Is(err, errFailConnectSocks5):
				err = errRequiredSocks5
			default:
				err = errors.New("Request through SOCKS5 proxy failed")
			}
		case err == nil && r.requireSocks5 && response.StatusCode != http.StatusOK:
			// An error response can echo request credentials.
			response.Body.Close()
			err = fmt.Errorf("HTTP status %d through SOCKS5 proxy", response.StatusCode)
		case err == nil:
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
