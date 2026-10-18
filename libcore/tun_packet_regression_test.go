//go:build with_gvisor

package libcore

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/gvisor/pkg/buffer"
	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/sagernet/gvisor/pkg/tcpip/header"
	"github.com/sagernet/gvisor/pkg/tcpip/link/channel"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	boxlog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/service"
)

// This replaces only the OS TUN device/platform FD. The inbound, gVisor stack,
// DNS hijack, router and direct outbound are the real locked core. It does not
// test Android VpnService, native TUN reads or real Android socket protection.
type packetTun struct {
	ep       *packetEndpoint
	replies  chan []byte
	observed chan []byte
	done     chan struct{}
	once     sync.Once
}

type packetEndpoint struct {
	*channel.Endpoint
	tun *packetTun
}

func (e *packetEndpoint) WritePackets(packets stack.PacketBufferList) (int, tcpip.Error) {
	for _, packet := range packets.AsSlice() {
		if _, err := e.tun.WritePacket(packet); err != nil {
			return 0, &tcpip.ErrAborted{}
		}
	}
	return packets.Len(), nil
}

func newPacketTun() *packetTun {
	t := &packetTun{replies: make(chan []byte, 64), observed: make(chan []byte, 64), done: make(chan struct{})}
	t.ep = &packetEndpoint{Endpoint: channel.New(64, 1500, ""), tun: t}
	return t
}
func (*packetTun) Name() (string, error)                { return "synthetic-tun", nil }
func (*packetTun) Start() error                         { return nil }
func (t *packetTun) Close() error                       { t.once.Do(func() { close(t.done); t.ep.Close() }); return nil }
func (*packetTun) Read([]byte) (int, error)             { return 0, io.EOF }
func (*packetTun) UpdateRouteOptions(tun.Options) error { return nil }
func (t *packetTun) Write(p []byte) (int, error) {
	select {
	case t.observed <- append([]byte(nil), p...):
	default:
	}
	select {
	case t.replies <- append([]byte(nil), p...):
		return len(p), nil
	case <-t.done:
		return 0, io.ErrClosedPipe
	}
}
func (t *packetTun) WritePacket(p *stack.PacketBuffer) (int, error) {
	var data []byte
	for _, part := range p.AsSlices() {
		data = append(data, part...)
	}
	return t.Write(data)
}
func (t *packetTun) NewEndpoint() (stack.LinkEndpoint, stack.NICOptions, error) {
	return t.ep, stack.NICOptions{}, nil
}
func (t *packetTun) inject(p []byte) {
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(p)})
	defer pkt.DecRef()
	protocol := header.IPv4ProtocolNumber
	if p[0]>>4 == 6 {
		protocol = header.IPv6ProtocolNumber
	}
	t.ep.InjectInbound(protocol, pkt)
}

type packetPlatform struct {
	regressionPlatform
	tun     *packetTun
	options tun.Options
}

type packetLog struct {
	sync.Mutex
	lines []string
}

func (l *packetLog) WriteMessage(_ boxlog.Level, message string) {
	l.Lock()
	defer l.Unlock()
	l.lines = append(l.lines, message)
}

func (*packetPlatform) UsePlatformInterface() bool { return true }
func (p *packetPlatform) OpenInterface(o *tun.Options, _ option.TunPlatformOptions) (tun.Tun, error) {
	p.options = *o
	return p.tun, nil
}
func (*packetPlatform) ProcessPlatformOptions(option.TunPlatformOptions) error { return nil }

func packetCore(t *testing.T, tunFields, routeRules string) *packetPlatform {
	t.Helper()
	config := fmt.Sprintf(`{"log":{"level":"debug"},"inbounds":[{"type":"tun","tag":"tun-in","interface_name":"synthetic-tun","address":["172.19.0.1/28","fdfe:dcba:9876::1/126"],"mtu":1500,"stack":"gvisor","udp_mapping":"endpoint_independent","udp_filtering":"endpoint_independent"%s}],"dns":{"servers":[{"type":"local","tag":"local"}],"rules":[{"domain":"probe.invalid","action":"predefined","answer":["probe.invalid. 60 IN A 192.0.2.9"]}],"final":"local"},"route":{"auto_detect_interface":true,"rules":[%s],"final":"direct"},"outbounds":[{"type":"direct","tag":"direct"}]}`, tunFields, routeRules)
	return packetCoreWithConfig(t, config)
}

func packetCoreWithConfig(t *testing.T, config string) *packetPlatform {
	t.Helper()
	p := &packetPlatform{tun: newPacketTun()}
	t.Cleanup(func() { p.tun.Close() })
	logs := new(packetLog)
	t.Cleanup(func() {
		if t.Failed() {
			logs.Lock()
			defer logs.Unlock()
			for _, line := range logs.lines {
				t.Log(line)
			}
		}
	})
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	ctx = box.Context(ctx, nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, p)
	var options option.Options
	data, overrides, err := prepareConfig(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := options.UnmarshalJSONContext(ctx, data); err != nil {
		t.Fatal(err)
	}
	b, err := box.New(box.Options{Options: options, Context: ctx, PlatformLogWriter: logs})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	if err = applySniffOverrides(b.Router().Rules(), overrides); err != nil {
		t.Fatal(err)
	}
	if err = b.Start(); err != nil {
		t.Fatal(err)
	}
	return p
}

func udpProbe(destination net.IP, port uint16, payload []byte) []byte {
	p := make([]byte, 28+len(payload))
	p[0], p[8], p[9] = 0x45, 64, 17
	binary.BigEndian.PutUint16(p[2:4], uint16(len(p)))
	copy(p[12:16], net.IPv4(172, 19, 0, 10).To4())
	copy(p[16:20], destination.To4())
	var sum uint32
	for i := 0; i < 20; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(p[i : i+2]))
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	binary.BigEndian.PutUint16(p[10:12], ^uint16(sum))
	binary.BigEndian.PutUint16(p[20:22], 40000)
	binary.BigEndian.PutUint16(p[22:24], port)
	binary.BigEndian.PutUint16(p[24:26], uint16(8+len(payload)))
	copy(p[28:], payload) // IPv4 UDP checksum zero is valid.
	return p
}

func receiveUDPPayload(t *testing.T, p *packetTun) []byte {
	t.Helper()
	select {
	case reply := <-p.replies:
		if len(reply) < 28 || reply[9] != 17 {
			t.Fatalf("unexpected TUN response (%d bytes)", len(reply))
		}
		if !net.IP(reply[16:20]).Equal(net.IPv4(172, 19, 0, 10)) || binary.BigEndian.Uint16(reply[22:24]) != 40000 {
			t.Fatal("reply lost original app destination")
		}
		return reply[int(reply[0]&15)*4+8:]
	case <-time.After(3 * time.Second):
		t.Fatal("no response packet from real core TUN stack")
		return nil
	}
}

func TestTUNPacketDNSHijackModeMatrix(t *testing.T) {
	for name, fields := range map[string]string{"internal-default": "", "auto-route-only": `,"auto_route":true`, "dns-hijack-only": `,"dns_mode":"hijack"`, "both": `,"auto_route":true,"dns_mode":"hijack"`, "internal-migrated-sniff-resolve": `,"sniff":true,"sniff_override_destination":true,"domain_strategy":"ipv4_only"`} {
		t.Run(name, func(t *testing.T) {
			p := packetCore(t, fields, `{"port":53,"action":"hijack-dns"}`)
			if (name == "internal-default" || name == "internal-migrated-sniff-resolve") && (p.options.AutoRoute || p.options.DNSMode != "") {
				t.Fatal("internal migration acquired official-export TUN defaults")
			}
			msg := new(dns.Msg)
			msg.SetQuestion("probe.invalid.", dns.TypeA)
			data, err := msg.Pack()
			if err != nil {
				t.Fatal(err)
			}
			p.tun.inject(udpProbe(net.IPv4(172, 19, 0, 2), 53, data))
			var response dns.Msg
			if err = response.Unpack(receiveUDPPayload(t, p.tun)); err != nil {
				t.Fatal(err)
			}
			if response.Id != msg.Id || len(response.Answer) != 1 || response.Answer[0].String() != "probe.invalid.\t60\tIN\tA\t192.0.2.9" {
				t.Fatalf("unexpected synthetic DNS response: %v", &response)
			}
			t.Logf("DNS request/response traversed real TUN stack; effective DNS mode=%s", p.options.DNSModeOrDefault())
		})
	}
}

func TestTUNPacketUDPForwarding(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go func() {
		b := make([]byte, 64)
		n, a, e := server.ReadFrom(b)
		if e == nil {
			server.WriteTo(b[:n], a)
		}
	}()
	port := server.LocalAddr().(*net.UDPAddr).Port
	p := packetCore(t, "", fmt.Sprintf(`{"ip_cidr":"192.0.2.9/32","action":"route-options","override_address":"127.0.0.1","override_port":%d}`, port))
	p.tun.inject(udpProbe(net.IPv4(192, 0, 2, 9), 32100, []byte("tun-probe")))
	if got := string(receiveUDPPayload(t, p.tun)); got != "tun-probe" {
		t.Fatalf("unexpected data-plane reply %q", got)
	}
	if p.calls.Load() == 0 {
		t.Fatal("TUN data plane bypassed platform socket control")
	}
	t.Logf("real TUN->router->loopback UDP->TUN response; simulated protect calls=%d", p.calls.Load())
}

// A second, client-side IP stack creates real TCP handshakes and IPv6 UDP
// checksums. Only byte queues connect it to the tested core's TUN endpoint.
func packetClient(t *testing.T, device *packetTun) *stack.Stack {
	t.Helper()
	ep := channel.New(64, 1500, "")
	client, err := tun.NewGVisorStack(ep)
	if err != nil {
		t.Fatal(err)
	}
	for _, prefix := range []string{"172.19.0.10/28", "fdfe:dcba:9876::3/126"} {
		addr := netip.MustParsePrefix(prefix)
		protocol := header.IPv4ProtocolNumber
		if addr.Addr().Is6() {
			protocol = header.IPv6ProtocolNumber
		}
		if err := client.AddProtocolAddress(tun.DefaultNIC, tcpip.ProtocolAddress{Protocol: protocol, AddressWithPrefix: tcpip.AddressWithPrefix{Address: tun.AddressFromAddr(addr.Addr()), PrefixLen: addr.Bits()}}, stack.AddressProperties{}); err != nil {
			t.Fatal(err)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for {
			pkt := ep.ReadContext(ctx)
			if pkt == nil {
				return
			}
			var data []byte
			for _, part := range pkt.AsSlices() {
				data = append(data, part...)
			}
			device.inject(data)
			pkt.DecRef()
		}
	}()
	go func() {
		defer wg.Done()
		for {
			select {
			case <-ctx.Done():
				return
			case data := <-device.replies:
				pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(data)})
				protocol := header.IPv4ProtocolNumber
				if data[0]>>4 == 6 {
					protocol = header.IPv6ProtocolNumber
				}
				ep.InjectInbound(protocol, pkt)
				pkt.DecRef()
			}
		}
	}()
	t.Cleanup(func() { cancel(); wg.Wait(); client.Close(); client.Wait(); ep.Close() })
	return client
}

func TestTUNPacketTCPDualStack(t *testing.T) {
	for _, address := range []string{"192.0.2.9", "2001:db8::9"} {
		t.Run(address, func(t *testing.T) {
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
			port := server.Addr().(*net.TCPAddr).Port
			p := packetCore(t, "", fmt.Sprintf(`{"action":"route","outbound":"direct","override_address":"127.0.0.1","override_port":%d}`, port))
			client := packetClient(t, p.tun)
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			addr := netip.MustParseAddr(address)
			protocol := header.IPv4ProtocolNumber
			if addr.Is6() {
				protocol = header.IPv6ProtocolNumber
			}
			c, err := gonet.DialContextTCP(ctx, client, tcpip.FullAddress{Addr: tun.AddressFromAddr(addr), Port: 32100}, protocol)
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			c.SetDeadline(time.Now().Add(3 * time.Second))
			if _, err = c.Write([]byte("tcp-probe")); err != nil {
				t.Fatal(err)
			}
			data := make([]byte, 9)
			if _, err = io.ReadFull(c, data); err != nil || string(data) != "tcp-probe" {
				t.Fatalf("TCP data plane: %v", err)
			}
			if p.calls.Load() == 0 {
				t.Fatal("TCP bypassed platform socket control")
			}
			t.Log("TCP SYN/ACK and data traversed real core TUN/router/outbound; loopback target, simulated protect")
		})
	}
}

func TestTUNPacketFakeIPDualStack(t *testing.T) {
	for _, queryType := range []uint16{dns.TypeA, dns.TypeAAAA} {
		t.Run(dns.TypeToString[queryType], func(t *testing.T) {
			server, err := net.ListenPacket("udp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer server.Close()
			go func() {
				b := make([]byte, 64)
				n, a, e := server.ReadFrom(b)
				if e == nil {
					server.WriteTo(b[:n], a)
				}
			}()
			config := `{"log":{"level":"debug"},"inbounds":[{"type":"tun","tag":"tun-in","interface_name":"synthetic-tun","address":["172.19.0.1/28","fdfe:dcba:9876::1/126"],"mtu":1500,"stack":"gvisor","udp_mapping":"endpoint_independent","udp_filtering":"endpoint_independent"}],"dns":{"servers":[{"type":"local","tag":"local"},{"type":"fakeip","tag":"fake","inet4_range":"198.18.0.0/15","inet6_range":"fc00::/18"}],"rules":[{"inbound":"tun-in","query_type":["A","AAAA"],"action":"route","server":"fake"}],"final":"local"},"route":{"auto_detect_interface":true,"rules":[{"port":53,"action":"hijack-dns"},{"domain":"probe.invalid","action":"route","outbound":"direct","override_address":"127.0.0.1"},{"action":"reject"}],"final":"direct"},"outbounds":[{"type":"direct","tag":"direct"}]}`
			p := packetCoreWithConfig(t, config)
			client := packetClient(t, p.tun)
			protocol := header.IPv4ProtocolNumber
			dnsAddr := netip.MustParseAddr("172.19.0.2")
			if queryType == dns.TypeAAAA {
				protocol = header.IPv6ProtocolNumber
				dnsAddr = netip.MustParseAddr("fdfe:dcba:9876::2")
			}
			query, err := gonet.DialUDP(client, nil, &tcpip.FullAddress{Addr: tun.AddressFromAddr(dnsAddr), Port: 53}, protocol)
			if err != nil {
				t.Fatal(err)
			}
			defer query.Close()
			query.SetDeadline(time.Now().Add(3 * time.Second))
			msg := new(dns.Msg)
			msg.SetQuestion("probe.invalid.", queryType)
			data, err := msg.Pack()
			if err != nil {
				t.Fatal(err)
			}
			if _, err = query.Write(data); err != nil {
				t.Fatal(err)
			}
			buf := make([]byte, 1500)
			n, err := query.Read(buf)
			if err != nil {
				t.Fatal(err)
			}
			var reply dns.Msg
			if err = reply.Unpack(buf[:n]); err != nil {
				t.Fatal(err)
			}
			if reply.Id != msg.Id || len(reply.Answer) != 1 {
				t.Fatal("missing FakeIP answer")
			}
			var fake netip.Addr
			switch rr := reply.Answer[0].(type) {
			case *dns.A:
				fake = netip.AddrFrom4([4]byte(rr.A.To4()))
			case *dns.AAAA:
				fake = netip.AddrFrom16([16]byte(rr.AAAA.To16()))
			default:
				t.Fatal("unexpected answer type")
			}
			cidr := "198.18.0.0/15"
			if queryType == dns.TypeAAAA {
				cidr = "fc00::/18"
			}
			if !netip.MustParsePrefix(cidr).Contains(fake) {
				t.Fatal("answer outside FakeIP range")
			}
			flow, err := gonet.DialUDP(client, nil, &tcpip.FullAddress{Addr: tun.AddressFromAddr(fake), Port: uint16(server.LocalAddr().(*net.UDPAddr).Port)}, protocol)
			if err != nil {
				t.Fatal(err)
			}
			defer flow.Close()
			flow.SetDeadline(time.Now().Add(3 * time.Second))
			if _, err = flow.Write([]byte("fake-probe")); err != nil {
				t.Fatal(err)
			}
			n, err = flow.Read(buf)
			if err != nil || string(buf[:n]) != "fake-probe" {
				for len(p.tun.observed) > 0 {
					packet := <-p.tun.observed
					offset := 20
					if packet[0]>>4 == 6 {
						offset = 40
					}
					if len(packet) >= offset+8 {
						t.Logf("TUN return IP-version=%d UDP source-port=%d destination-port=%d", packet[0]>>4, binary.BigEndian.Uint16(packet[offset:offset+2]), binary.BigEndian.Uint16(packet[offset+2:offset+4]))
					}
				}
				t.Fatalf("FakeIP reverse mapping/domain route/UDP: %v", err)
			}
			if p.calls.Load() == 0 {
				t.Fatal("FakeIP data bypassed socket control")
			}
			t.Log("DNS over same-family TUN -> FakeIP -> domain-only route -> loopback UDP -> original FakeIP response succeeded")
		})
	}
}
