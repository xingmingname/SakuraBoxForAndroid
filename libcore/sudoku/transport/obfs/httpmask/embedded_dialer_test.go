package httpmask

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func TestEmbeddedDialerUsedForEveryHTTPMaskMode(t *testing.T) {
	for _, mode := range []string{"ws", "auto", "stream", "poll"} {
		for _, tls := range []bool{false, true} {
			t.Run(mode+map[bool]string{true: "/tls", false: "/plain"}[tls], func(t *testing.T) {
				stopDial := errors.New("embedding dialer sentinel")
				var calls atomic.Int32
				ctx, cancel := context.WithTimeout(context.Background(), time.Second)
				defer cancel()
				pool := new(TransportPool)
				defer pool.Close()
				_, err := DialTunnel(ctx, "127.0.0.1:12345", TunnelDialOptions{
					Mode: mode, TLSEnabled: tls, HostOverride: "localhost", Multiplex: "off", TransportPool: pool,
					DialContext: func(_ context.Context, network, address string) (net.Conn, error) {
						calls.Add(1)
						if network != "tcp" || address != "127.0.0.1:12345" {
							t.Errorf("unexpected dial target: %s %s", network, address)
						}
						return nil, stopDial
					},
				})
				if calls.Load() == 0 || err == nil {
					t.Fatalf("embedding dialer bypassed: calls=%d err=%v", calls.Load(), err)
				}
			})
		}
	}
}
