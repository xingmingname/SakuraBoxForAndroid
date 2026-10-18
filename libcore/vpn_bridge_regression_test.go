package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

// Characterize the actual core with the current production monitor, but a
// simulated protect callback. This does not execute Android VpnService.protect.
type regressionPlatform struct {
	adapter.PlatformInterface
	calls atomic.Int32
	fail  bool
}

func (*regressionPlatform) Initialize(adapter.NetworkManager) error  { return nil }
func (*regressionPlatform) UsePlatformDefaultInterfaceMonitor() bool { return true }
func (*regressionPlatform) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor {
	return &interfaceMonitorStub{}
}
func (*regressionPlatform) UsePlatformNetworkInterfaces() bool          { return false }
func (*regressionPlatform) UsePlatformAutoDetectInterfaceControl() bool { return true }
func (*regressionPlatform) UsePlatformWIFIMonitor() bool                { return false }
func (*regressionPlatform) UsePlatformNeighborResolver() bool           { return false }
func (*regressionPlatform) UsePlatformConnectionOwnerFinder() bool      { return false }
func (*regressionPlatform) UnderNetworkExtension() bool                 { return false }
func (p *regressionPlatform) AutoDetectInterfaceControl(fd int) error {
	p.calls.Add(1)
	if fd < 0 {
		return errors.New("invalid socket descriptor")
	}
	if p.fail {
		return errors.New("simulated protect failure")
	}
	return nil
}

func regressionCore(t *testing.T, p *regressionPlatform) (*box.Box, context.Context) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, p)
	var opts option.Options
	if err := opts.UnmarshalJSONContext(ctx, []byte(`{"log":{"disabled":true},"route":{"auto_detect_interface":true,"final":"direct"},"outbounds":[{"type":"direct","tag":"direct"}]}`)); err != nil {
		t.Fatal(err)
	}
	b, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	return b, ctx
}

func TestVPNBridgeSocketControlAndDataPlane(t *testing.T) {
	for _, network := range []string{"tcp", "udp"} {
		for _, fail := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/protect-fails-%v", network, fail), func(t *testing.T) {
				p := &regressionPlatform{fail: fail}
				b, ctx := regressionCore(t, p)
				// The nil monitor pauses network state even though direct TCP/UDP can
				// work: it is a real pre-existing defect, not proof of this phone failure.
				t.Logf("production_monitor_default_nil=%v network_paused=%v", b.Network().InterfaceMonitor().DefaultInterface() == nil, service.FromContext[pause.Manager](ctx).IsNetworkPaused())
				ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
				defer cancel()
				if network == "tcp" {
					server, err := net.Listen("tcp", "127.0.0.1:0")
					if err != nil {
						t.Fatal(err)
					}
					defer server.Close()
					go func() {
						c, e := server.Accept()
						if e == nil {
							defer c.Close()
							io.Copy(c, c)
						}
					}()
					c, err := b.Outbound().Default().DialContext(ctx, "tcp", M.ParseSocksaddr(server.Addr().String()))
					if fail {
						if err == nil || !strings.Contains(err.Error(), "simulated protect failure") {
							t.Fatalf("control failure not propagated: %v", err)
						}
					} else {
						if err != nil {
							t.Fatal(err)
						}
						defer c.Close()
						c.SetDeadline(time.Now().Add(time.Second))
						if _, err = c.Write([]byte("probe")); err != nil {
							t.Fatal(err)
						}
						buf := make([]byte, 5)
						if _, err = io.ReadFull(c, buf); err != nil || string(buf) != "probe" {
							t.Fatalf("TCP data failed: %v", err)
						}
					}
				} else {
					server, err := net.ListenPacket("udp", "127.0.0.1:0")
					if err != nil {
						t.Fatal(err)
					}
					defer server.Close()
					go func() {
						buf := make([]byte, 64)
						n, addr, e := server.ReadFrom(buf)
						if e == nil {
							server.WriteTo(buf[:n], addr)
						}
					}()
					c, err := b.Outbound().Default().ListenPacket(ctx, M.ParseSocksaddr(server.LocalAddr().String()))
					if fail {
						if err == nil || !strings.Contains(err.Error(), "simulated protect failure") {
							t.Fatalf("control failure not propagated: %v", err)
						}
					} else {
						if err != nil {
							t.Fatal(err)
						}
						defer c.Close()
						c.SetDeadline(time.Now().Add(time.Second))
						if _, err = c.WriteTo([]byte("probe"), server.LocalAddr()); err != nil {
							t.Fatal(err)
						}
						buf := make([]byte, 64)
						n, _, err := c.ReadFrom(buf)
						if err != nil || string(buf[:n]) != "probe" {
							t.Fatalf("UDP data failed: %v", err)
						}
					}
				}
				if p.calls.Load() == 0 {
					t.Fatal("core bypassed platform socket control")
				}
				t.Logf("simulated_control_calls=%d", p.calls.Load())
			})
		}
	}
}
