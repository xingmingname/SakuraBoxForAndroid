package httpmask

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestHTTPMaskFallbackUpgradeHonorsCancellation(t *testing.T) {
	for _, mode := range []string{"stream", "poll", "auto"} {
		t.Run(mode, func(t *testing.T) {
			releaseServer := make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				switch req.URL.Path {
				case "/session":
					fmt.Fprint(w, "token=synthetic\ncap=upload-seq\n")
				case "/stream":
					w.WriteHeader(http.StatusOK)
					w.(http.Flusher).Flush()
					select {
					case <-releaseServer:
					case <-req.Context().Done():
					}
				default:
					if req.URL.Query().Get("close") == "1" || req.URL.Query().Get("fin") == "1" {
						w.WriteHeader(http.StatusOK)
						return
					}
					_, _ = io.Copy(io.Discard, req.Body)
					w.WriteHeader(http.StatusOK)
				}
			}))
			defer func() { close(releaseServer); server.CloseClientConnections(); server.Close() }()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			entered := make(chan net.Conn, 1)
			finished := make(chan error, 1)
			pool := new(TransportPool)
			defer pool.Close()
			go func() {
				conn, err := DialTunnel(ctx, strings.TrimPrefix(server.URL, "http://"), TunnelDialOptions{
					Mode: mode, Multiplex: "off", TransportPool: pool,
					EarlyHandshake: &ClientEarlyHandshake{RequestPayload: []byte("synthetic"), Ready: func() bool { return false }},
					Upgrade: func(conn net.Conn) (net.Conn, error) {
						entered <- conn
						var one [1]byte
						_, err := conn.Read(one[:])
						return conn, err
					},
				})
				if conn != nil {
					_ = conn.Close()
				}
				finished <- err
			}()
			var pending net.Conn
			select {
			case pending = <-entered:
			case err := <-finished:
				t.Fatalf("upgrade not reached: %v", err)
			case <-time.After(3 * time.Second):
				t.Fatal("upgrade not reached")
			}
			cancel()
			select {
			case err := <-finished:
				if err == nil {
					t.Error("canceled upgrade returned success")
				}
			case <-time.After(700 * time.Millisecond):
				t.Error("RISK-01 reproduced: context cancellation did not terminate HTTPMask fallback upgrade")
				_ = pending.Close()
				select {
				case <-finished:
				case <-time.After(3 * time.Second):
					t.Fatal("probe cleanup failed")
				}
			}
		})
	}
}

func TestUpgradeDetachesSuccessfulConnectionFromDialContext(t *testing.T) {
	for _, early := range []bool{false, true} {
		t.Run(fmt.Sprint(early), func(t *testing.T) {
			raw, peer := net.Pipe()
			defer raw.Close()
			defer peer.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			opts := TunnelDialOptions{Upgrade: func(c net.Conn) (net.Conn, error) { return c, nil }}
			if early {
				opts.EarlyHandshake = &ClientEarlyHandshake{Ready: func() bool { return true }, WrapConn: func(c net.Conn) (net.Conn, error) { return c, nil }}
			}
			conn, err := upgradeDialedConn(ctx, raw, opts)
			if err != nil {
				t.Fatal(err)
			}
			cancel()
			_ = conn.SetDeadline(time.Now().Add(time.Second))
			done := make(chan error, 1)
			go func() { _, e := peer.Write([]byte("x")); done <- e }()
			var one [1]byte
			if _, err = io.ReadFull(conn, one[:]); err != nil || one[0] != 'x' {
				t.Fatalf("successful connection was closed by old dial context: %v", err)
			}
			if err = <-done; err != nil {
				t.Fatal(err)
			}
		})
	}
}
func TestUpgradeRejectsCanceledAndFailedConstruction(t *testing.T) {
	for _, before := range []bool{true, false} {
		t.Run(fmt.Sprint(before), func(t *testing.T) {
			raw, peer := net.Pipe()
			defer raw.Close()
			defer peer.Close()
			ctx, cancel := context.WithCancel(context.Background())
			if before {
				cancel()
			}
			opts := TunnelDialOptions{Upgrade: func(c net.Conn) (net.Conn, error) { cancel(); return c, nil }}
			conn, err := upgradeDialedConn(ctx, raw, opts)
			if conn != nil || err != context.Canceled {
				t.Fatalf("canceled upgrade returned connection: %v", err)
			}
			_ = peer.SetReadDeadline(time.Now().Add(time.Second))
			var one [1]byte
			if _, err = peer.Read(one[:]); err != io.EOF {
				t.Fatal("canceled underlying connection stayed open")
			}
		})
	}
	raw, peer := net.Pipe()
	defer peer.Close()
	_, err := upgradeDialedConn(context.Background(), raw, TunnelDialOptions{Upgrade: func(net.Conn) (net.Conn, error) { return nil, io.ErrUnexpectedEOF }})
	if err != io.ErrUnexpectedEOF {
		t.Fatalf("upgrade error changed: %v", err)
	}
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	var one [1]byte
	if _, err = peer.Read(one[:]); err != io.EOF {
		t.Fatal("failed upgrade did not close raw connection")
	}
}
