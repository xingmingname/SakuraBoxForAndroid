package libcore

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing/protocol/socks/socks5"
)

func subscriptionProxyPort(t *testing.T, handler func(net.Conn)) int32 {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := int32(listener.Addr().(*net.TCPAddr).Port)
	if handler == nil {
		listener.Close()
		return port
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(3 * time.Second))
				handler(conn)
			}()
		}
	}()
	return port
}

func subscriptionProxyRequest(t *testing.T, client HTTPClient, target string, insecure bool) (string, error) {
	t.Helper()
	defer client.Close()
	request := client.NewRequest()
	if insecure {
		request.AllowInsecure()
	}
	if err := request.SetURL(target); err != nil {
		t.Fatal("invalid loopback fixture URL")
	}
	response, err := request.Execute()
	if err != nil {
		return "", err
	}
	content, err := response.GetContent()
	return string(content), err
}

func relaySubscriptionProxy(client net.Conn, target string) bool {
	reader := bufio.NewReader(client)
	if _, err := socks5.ReadAuthRequest(reader); err != nil {
		return false
	}
	if _, err := client.Write([]byte{5, socks5.AuthTypeNotRequired}); err != nil {
		return false
	}
	request, err := socks5.ReadRequest(reader)
	if err != nil || request.Command != socks5.CommandConnect || request.Destination.String() != target {
		return false
	}
	server, err := net.DialTimeout("tcp", target, time.Second)
	if err != nil {
		return false
	}
	defer server.Close()
	server.SetDeadline(time.Now().Add(3 * time.Second))
	if err := socks5.WriteResponse(client, socks5.Response{}); err != nil {
		return false
	}
	upload := make(chan struct{})
	go func() {
		defer close(upload)
		io.Copy(server, reader)
	}()
	io.Copy(client, server)
	client.Close()
	<-upload
	return true
}

func TestSubscriptionRequiredSOCKS5SuccessAndSafeHTTPError(t *testing.T) {
	const token = "synthetic-subscription-token"
	var proxied, received atomic.Int32
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received.Add(1)
		if r.URL.Path == "/denied" {
			http.Error(w, token, http.StatusForbidden)
			return
		}
		fmt.Fprint(w, "subscription fixture")
	}))
	defer origin.Close()
	port := subscriptionProxyPort(t, func(conn net.Conn) {
		proxied.Add(1)
		relaySubscriptionProxy(conn, origin.Listener.Addr().String())
	})
	body, err := subscriptionProxyRequest(t, NewSocks5OnlyHttpClient(port), origin.URL, false)
	if err != nil || body != "subscription fixture" || received.Load() != 1 || proxied.Load() != 1 {
		t.Fatalf("required proxy did not serve the subscription: %v", err)
	}
	_, err = subscriptionProxyRequest(t, NewSocks5OnlyHttpClient(port), origin.URL+"/denied?token="+token, false)
	if err == nil || !strings.Contains(err.Error(), "403") || strings.Contains(err.Error(), token) {
		t.Fatal("protected HTTP error must keep the status and omit the response body and URL")
	}
}

func TestSubscriptionRequiredSOCKS5NeverDialsOriginOnFailure(t *testing.T) {
	const token = "synthetic-subscription-token"
	var directHits atomic.Int32
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		directHits.Add(1)
		fmt.Fprint(w, "unexpected direct request")
	}))
	defer origin.Close()
	addr := origin.Listener.Addr().(*net.TCPAddr)
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: addr.IP, Port: addr.Port})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	for _, failure := range []struct {
		name    string
		handler func(net.Conn)
		error   string
	}{
		{"listener unavailable", nil, "Required SOCKS5"},
		{"rejected handshake", func(conn net.Conn) {
			if _, err := socks5.ReadAuthRequest(bufio.NewReader(conn)); err == nil {
				conn.Write([]byte{5, socks5.AuthTypeNoAcceptedMethods})
			}
		}, "Required SOCKS5"},
		{"stalled handshake", func(conn net.Conn) { io.Copy(io.Discard, conn) }, "timed out"},
		{"tunnel disconnected", func(conn net.Conn) {
			reader := bufio.NewReader(conn)
			if _, err := socks5.ReadAuthRequest(reader); err != nil {
				return
			}
			conn.Write([]byte{5, socks5.AuthTypeNotRequired})
			if _, err := socks5.ReadRequest(reader); err == nil {
				socks5.WriteResponse(conn, socks5.Response{})
			}
		}, "SOCKS5 proxy failed"},
	} {
		t.Run(failure.name, func(t *testing.T) {
			port := subscriptionProxyPort(t, failure.handler)
			client := NewSocks5OnlyHttpClient(port)
			client.SetTimeout(350)
			client.TryH3Direct() // Even an accidental fallback flag must not bypass the policy.
			_, err := subscriptionProxyRequest(t, client, origin.URL+"?token="+token, true)
			if err == nil || !strings.Contains(err.Error(), failure.error) || strings.Contains(err.Error(), token) {
				t.Fatalf("unexpected protected error for %s: %v", failure.name, err)
			}
			if directHits.Load() != 0 {
				t.Fatal("origin received direct HTTPS")
			}
		})
	}
	udp.SetReadDeadline(time.Now().Add(60 * time.Millisecond))
	if _, _, err := udp.ReadFromUDP(make([]byte, 2048)); err == nil {
		t.Fatal("origin received a direct QUIC packet")
	} else if e, ok := err.(net.Error); !ok || !e.Timeout() {
		t.Fatal(err)
	}
}

func TestSubscriptionRequiredSOCKS5RedirectStaysProtectedAfterNetworkChange(t *testing.T) {
	var firstHits, secondHits, attempts atomic.Int32
	second := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		secondHits.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer second.Close()
	first := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		firstHits.Add(1)
		http.Redirect(w, r, second.URL, http.StatusFound)
	}))
	defer first.Close()
	port := subscriptionProxyPort(t, func(conn net.Conn) {
		if attempts.Add(1) == 1 {
			relaySubscriptionProxy(conn, first.Listener.Addr().String())
		}
		// Simulate the local proxy losing its underlay before the redirected dial.
	})
	client := NewSocks5OnlyHttpClient(port)
	client.TryH3Direct()
	_, err := subscriptionProxyRequest(t, client, first.URL, false)
	if err == nil || !strings.Contains(err.Error(), "SOCKS5") ||
		firstHits.Load() != 1 || attempts.Load() < 2 || secondHits.Load() != 0 {
		t.Fatalf("redirect did not stay behind the failing proxy: %v", err)
	}
}

func TestSubscriptionOptionalSOCKS5KeepsLegacyDirectFallback(t *testing.T) {
	var directHits atomic.Int32
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		directHits.Add(1)
		fmt.Fprint(w, "legacy fallback")
	}))
	defer origin.Close()
	for _, h3Fallback := range []bool{false, true} {
		client := NewHttpClient()
		client.TrySocks5(subscriptionProxyPort(t, nil))
		if h3Fallback {
			client.TryH3Direct()
		}
		body, err := subscriptionProxyRequest(t, client, origin.URL, false)
		if err != nil || body != "legacy fallback" {
			t.Fatalf("ordinary request lost its direct fallback: %v", err)
		}
	}
	if directHits.Load() != 2 {
		t.Fatal("expected both allowed-direct paths to reach the origin")
	}
}
