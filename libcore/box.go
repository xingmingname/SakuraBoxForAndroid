package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var mainInstance atomic.Pointer[BoxInstance]
var mainAccess sync.Mutex

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		if instance := mainInstance.Load(); instance != nil {
			instance.resetNetwork()
		}
	} else {
		log.Println("TODO: Reset user connections")
	}
}

// Serialize the core's full reset with Start/Close. In particular, a main
// instance can be published before Start, and a queued callback can outlive it.
func (b *BoxInstance) resetNetwork() {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || !b.started || b.Box == nil || b.ctx == nil || b.ctx.Err() != nil {
		return
	}
	// The locked core closes its ConnectionManager, notifies protocol/inbound/
	// endpoint listeners, then resets router caches. Do not close trackers again.
	b.Box.Network().ResetNetwork(b.ctx)
	log.Println("Reset sing-box connections and network state done")
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	ctx     context.Context
	cancel  context.CancelFunc
	state   int
	started bool

	v2api          *boxapi.SbV2rayServer
	selector       *group.Selector
	pauseManager   pause.Manager
	trafficTracker *trafficcontrol.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })
	return newSingBoxInstance(config, localTransport, boxPlatformInterfaceInstance)
}

func newSingBoxInstance(config string, localTransport LocalDNSTransport, platform adapter.PlatformInterface) (b *BoxInstance, err error) {
	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	defer func() {
		if b == nil {
			cancel()
		}
	}()
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(), certificate.NewRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, platform)

	// parse options
	var options option.Options
	configBytes, sniffOverrides, err := prepareConfig(config)
	if err != nil {
		return nil, fmt.Errorf("migrate config: %w", err)
	}
	err = options.UnmarshalJSONContext(ctx, configBytes)
	if err != nil {
		return nil, fmt.Errorf("decode config: %v", err)
	}
	// Test-only isolation policy: check final options before constructing any
	// dialers or auxiliary clients. The main VPN keeps its original behavior.
	if _, independentTest := platform.(*testPlatformInterface); independentTest {
		if err = validateTestNetworkConfig(options); err != nil {
			return nil, err
		}
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}
	if err = applySniffOverrides(instance.Router().Rules(), sniffOverrides); err != nil {
		instance.Close()
		return nil, err
	}

	b = &BoxInstance{
		Box:            instance,
		ctx:            ctx,
		cancel:         cancel,
		pauseManager:   service.FromContext[pause.Manager](ctx),
		trafficTracker: service.PtrFromContext[trafficcontrol.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		err = b.Box.Start()
		b.started = err == nil
		return err
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// clear main instance
	mainAccess.Lock()
	if mainInstance.CompareAndSwap(b, nil) {
		goServeProtect(false)
	}
	mainAccess.Unlock()

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state == 2 {
		return
	}
	mainAccess.Lock()
	defer mainAccess.Unlock()
	mainInstance.Store(b)
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	var connectionTracker adapter.ConnectionTracker
	// test i
	if i != nil {
		if i.v2api != nil {
			connectionTracker = i.v2api.StatsService()
		}
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(i.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test direct
	instance := mainInstance.Load()
	if instance == nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(nil, nil), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test mainInstance
	if instance.v2api != nil {
		connectionTracker = instance.v2api.StatsService()
	}
	return speedtest.UrlTest(boxapi.CreateProxyHttpClient(instance.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtectWithError("protect_path", false, 0, func(fd int) error {
			return intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
