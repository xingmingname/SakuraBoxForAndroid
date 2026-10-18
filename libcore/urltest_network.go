package libcore

import (
	"fmt"
	"libcore/device"
)

// TestNetworkControl belongs to one test, never to the process or main VPN.
// Both methods must fail when its pinned physical network is no longer usable.
type TestNetworkControl interface {
	CheckNetwork() error
	BindSocket(fd int32) error
}

type testPlatformInterface struct {
	*boxPlatformInterfaceWrapper
	control TestNetworkControl
}

func (p *testPlatformInterface) AutoDetectInterfaceControl(fd int) error {
	if err := p.control.CheckNetwork(); err != nil {
		return fmt.Errorf("URL Test network: %w", err)
	}
	if err := p.control.BindSocket(int32(fd)); err != nil {
		return fmt.Errorf("URL Test socket binding: %w", err)
	}
	return p.control.CheckNetwork()
}

type testLocalDNSTransport struct {
	LocalDNSTransport
	control TestNetworkControl
}

func (p *testLocalDNSTransport) CheckNetwork() error { return p.control.CheckNetwork() }

// NewTestSingBoxInstance keeps the existing constructor and global platform
// untouched. The supplied resolver and controller must use the same Network.
func NewTestSingBoxInstance(config string, resolver LocalDNSTransport, control TestNetworkControl) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewTestSingBoxInstance", func(e error) { err = e })
	if resolver == nil || control == nil {
		return nil, fmt.Errorf("URL Test requires a network controller and resolver")
	}
	if err = control.CheckNetwork(); err != nil {
		return nil, err
	}
	platform := &testPlatformInterface{new(boxPlatformInterfaceWrapper), control}
	return newSingBoxInstance(config, &testLocalDNSTransport{resolver, control}, platform)
}
