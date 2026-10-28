package libcore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"libcore/procfs"
	"log"
	"net/netip"
	"strings"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/process"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	N "github.com/sagernet/sing/common/network"
)

var boxPlatformInterfaceInstance adapter.PlatformInterface = &boxPlatformInterfaceWrapper{}

type boxPlatformInterfaceWrapper struct {
	myInterfaceAddresses []netip.Addr
}

var _ adapter.PlatformInterface = (*boxPlatformInterfaceWrapper)(nil)

func (w *boxPlatformInterfaceWrapper) Initialize(adapter.NetworkManager) error { return nil }

func (w *boxPlatformInterfaceWrapper) UsePlatformAutoDetectInterfaceControl() bool { return true }

func (w *boxPlatformInterfaceWrapper) AutoDetectInterfaceControl(fd int) error {
	if !isBgProcess {
		return sendFdToProtect(fd, "protect_path")
	}
	return intfBox.AutoDetectInterfaceControl(int32(fd))
}

func (w *boxPlatformInterfaceWrapper) UsePlatformInterface() bool { return true }

func (w *boxPlatformInterfaceWrapper) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, E.New("android: unsupported uid options")
	}
	if len(options.IncludeAndroidUser) > 0 {
		return nil, E.New("android: unsupported android_user option")
	}
	a, _ := json.Marshal(options)
	b, _ := json.Marshal(platformOptions)
	tunFd, err := intfBox.OpenTun(string(a), string(b))
	if err != nil {
		return nil, fmt.Errorf("intfBox.OpenTun: %w", err)
	}
	tunFd, err = syscall.Dup(tunFd)
	if err != nil {
		return nil, fmt.Errorf("syscall.Dup: %w", err)
	}
	options.FileDescriptor = tunFd
	w.myInterfaceAddresses = w.myInterfaceAddresses[:0]
	for _, prefix := range append(append([]netip.Prefix{}, options.Inet4Address...), options.Inet6Address...) {
		w.myInterfaceAddresses = append(w.myInterfaceAddresses, prefix.Addr())
	}
	return tun.New(*options)
}

func (*boxPlatformInterfaceWrapper) ProcessPlatformOptions(option.TunPlatformOptions) error {
	return nil
}
func (*boxPlatformInterfaceWrapper) UsePlatformDefaultInterfaceMonitor() bool { return true }
func (*boxPlatformInterfaceWrapper) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor {
	return &interfaceMonitorStub{}
}
func (*boxPlatformInterfaceWrapper) UsePlatformNetworkInterfaces() bool { return false }
func (*boxPlatformInterfaceWrapper) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, errors.New("Android network interface enumeration is not provided by the app bridge")
}
func (*boxPlatformInterfaceWrapper) UnderNetworkExtension() bool              { return false }
func (*boxPlatformInterfaceWrapper) NetworkExtensionIncludeAllNetworks() bool { return false }
func (*boxPlatformInterfaceWrapper) ClearDNSCache()                           {}
func (*boxPlatformInterfaceWrapper) RequestPermissionForWIFIState() error     { return nil }
func (*boxPlatformInterfaceWrapper) ReadWIFIState(context.Context) adapter.WIFIState {
	state := strings.SplitN(intfBox.WIFIState(), ",", 2)
	if len(state) != 2 {
		return adapter.WIFIState{}
	}
	return adapter.WIFIState{SSID: state[0], BSSID: state[1]}
}
func (*boxPlatformInterfaceWrapper) UsePlatformConnectionOwnerFinder() bool { return true }
func (*boxPlatformInterfaceWrapper) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	var uid int32
	if useProcfs {
		network := N.NetworkTCP
		if request.IpProtocol == syscall.IPPROTO_UDP {
			network = N.NetworkUDP
		}
		sourceAddress, err := netip.ParseAddr(request.SourceAddress)
		if err != nil {
			return nil, err
		}
		destinationAddress, err := netip.ParseAddr(request.DestinationAddress)
		if err != nil {
			return nil, err
		}
		source := netip.AddrPortFrom(sourceAddress, uint16(request.SourcePort))
		destination := netip.AddrPortFrom(destinationAddress, uint16(request.DestinationPort))
		uid = procfs.ResolveSocketByProcSearch(network, source, destination)
		if uid == -1 {
			return nil, process.ErrNotFound
		}
	} else {
		var err error
		uid, err = intfBox.FindConnectionOwner(request.IpProtocol, request.SourceAddress, request.SourcePort, request.DestinationAddress, request.DestinationPort)
		if err != nil {
			return nil, err
		}
	}
	packageName, _ := intfBox.PackageNameByUid(uid)
	return &adapter.ConnectionOwner{UserId: uid, AndroidPackageNames: []string{packageName}}, nil
}
func (*boxPlatformInterfaceWrapper) UsePlatformWIFIMonitor() bool                 { return false }
func (*boxPlatformInterfaceWrapper) UsePlatformNotification() bool                { return false }
func (*boxPlatformInterfaceWrapper) SendNotification(*adapter.Notification) error { return nil }
func (*boxPlatformInterfaceWrapper) CancelNotification(string, int32) error       { return nil }
func (w *boxPlatformInterfaceWrapper) MyInterfaceAddress() []netip.Addr {
	return w.myInterfaceAddresses
}
func (*boxPlatformInterfaceWrapper) UsePlatformNeighborResolver() bool { return false }
func (*boxPlatformInterfaceWrapper) StartNeighborMonitor(adapter.NeighborUpdateListener) error {
	return errors.New("Android neighbor monitor is unavailable")
}
func (*boxPlatformInterfaceWrapper) CloseNeighborMonitor(adapter.NeighborUpdateListener) error {
	return nil
}
func (*boxPlatformInterfaceWrapper) UsePlatformShell() bool { return false }
func (*boxPlatformInterfaceWrapper) CheckPlatformShell() error {
	return errors.New("Android platform shell is unavailable")
}
func (*boxPlatformInterfaceWrapper) OpenShellSession(*adapter.PlatformUser, string, []string, string, int32, int32) (adapter.ShellSession, error) {
	return nil, errors.New("Android platform shell is unavailable")
}
func (*boxPlatformInterfaceWrapper) LookupUser(string) (*adapter.PlatformUser, error) {
	return nil, errors.New("Android user lookup is unavailable")
}
func (*boxPlatformInterfaceWrapper) LookupSFTPServer() (string, error) {
	return "", errors.New("Android SFTP is unavailable")
}
func (*boxPlatformInterfaceWrapper) ReadSystemSSHHostKey() ([]byte, error) {
	return nil, errors.New("Android SSH host key is unavailable")
}
func (*boxPlatformInterfaceWrapper) TailscaleHostname() string { return "" }
func (*boxPlatformInterfaceWrapper) UsePlatformBridge() bool   { return false }
func (*boxPlatformInterfaceWrapper) CreateBridge(adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, errors.New("Android bridge is unavailable")
}

// io.Writer keeps the legacy platform logging hook available to sing-box.
var disableSingBoxLog = false

func (*boxPlatformInterfaceWrapper) Write(p []byte) (int, error) {
	if !disableSingBoxLog {
		log.Print(string(p))
	}
	return len(p), nil
}

type boxPlatformLogWriterWrapper struct{}

var boxPlatformLogWriter sblog.PlatformWriter = &boxPlatformLogWriterWrapper{}

func (*boxPlatformLogWriterWrapper) DisableColors() bool { return true }
func (*boxPlatformLogWriterWrapper) WriteMessage(_ uint8, message string) {
	if !strings.HasSuffix(message, "\n") {
		message += "\n"
	}
	safeNativeLog.Write([]byte(message))
}
