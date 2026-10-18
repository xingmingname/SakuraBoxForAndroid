package libcore

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/option"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

type pinnedControl struct {
	calls     atomic.Int32
	valid     atomic.Bool
	bindError error
}

func newPinnedControl() *pinnedControl { p := new(pinnedControl); p.valid.Store(true); return p }
func (p *pinnedControl) CheckNetwork() error {
	if !p.valid.Load() {
		return errors.New("synthetic network lost")
	}
	return nil
}
func (p *pinnedControl) BindSocket(fd int32) error {
	p.calls.Add(1)
	if fd < 0 {
		return errors.New("invalid socket")
	}
	return p.bindError
}

type pinnedResolver struct{ calls atomic.Int32 }

func (*pinnedResolver) Raw() bool            { return false }
func (*pinnedResolver) NetworkHandle() int64 { return 42 }
func (p *pinnedResolver) Lookup(c *ExchangeContext, network, domain string) error {
	p.calls.Add(1)
	c.Success("127.0.0.1")
	return nil
}
func (*pinnedResolver) Exchange(c *ExchangeContext, msg []byte) error {
	return errors.New("unexpected raw query")
}
func (*pinnedResolver) DNSServers(string) (string, error) { return "127.0.0.1", nil }

func pinnedClient(t *testing.T, peer *httptest.Server, mode string, badAuth bool, p *pinnedControl, resolver *pinnedResolver) *BoxInstance {
	t.Helper()
	u, _ := url.Parse(peer.URL)
	host, portText, _ := net.SplitHostPort(u.Host)
	port, _ := strconv.Atoi(portText)
	password := "synthetic-only"
	if badAuth {
		password = "intentionally-invalid"
	}
	node := map[string]any{"type": "http", "tag": "B", "server": host, "server_port": port, "username": "audit-user", "password": password}
	config := map[string]any{"log": map[string]any{"disabled": true}, "outbounds": []any{node}, "route": map[string]any{"auto_detect_interface": true, "final": "B"}}
	if mode != "ip" {
		node["server"] = "node.invalid"
		server := map[string]any{"type": "local", "tag": "dns-direct"}
		if mode == "udp" {
			port, _ := pinnedDNS(t)
			server = map[string]any{"type": "udp", "tag": "dns-direct", "server": "127.0.0.1", "server_port": port}
		}
		config["dns"] = map[string]any{"servers": []any{server}, "final": "dns-direct"}
		node["domain_resolver"] = map[string]any{"server": "dns-direct", "strategy": "ipv4_only"}
	}
	raw, e := json.Marshal(config)
	if e != nil {
		t.Fatal(e)
	}
	b, e := NewTestSingBoxInstance(string(raw), resolver, p)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { b.Close() })
	if e = b.Start(); e != nil {
		t.Fatal(e)
	}
	return b
}
func pinnedProxy(t *testing.T, targetAddr string) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	hits := new(atomic.Int32)
	auth := "Basic " + base64.StdEncoding.EncodeToString([]byte("audit-user:synthetic-only"))
	peer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if r.Method != "CONNECT" || r.Host != targetAddr {
			w.WriteHeader(400)
			return
		}
		if r.Header.Get("Proxy-Authorization") != auth {
			w.WriteHeader(407)
			return
		}
		up, e := net.DialTimeout("tcp", targetAddr, time.Second)
		if e != nil {
			w.WriteHeader(502)
			return
		}
		c, rw, e := w.(http.Hijacker).Hijack()
		if e != nil {
			up.Close()
			return
		}
		fmt.Fprint(rw, "HTTP/1.1 200 Connection Established\r\n\r\n")
		rw.Flush()
		go func() { defer c.Close(); defer up.Close(); go io.Copy(c, up); io.Copy(up, rw) }()
	}))
	t.Cleanup(peer.Close)
	return peer, hits
}

func pinnedDNS(t *testing.T) (int, *atomic.Int32) {
	t.Helper()
	listener, e := net.ListenPacket("udp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	hits := new(atomic.Int32)
	server := &dns.Server{PacketConn: listener, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, r *dns.Msg) {
		hits.Add(1)
		a := new(dns.Msg)
		a.SetReply(r)
		for _, q := range r.Question {
			if q.Qtype == dns.TypeA {
				a.Answer = append(a.Answer, &dns.A{Hdr: dns.RR_Header{Name: q.Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 1}, A: net.IPv4(127, 0, 0, 1)})
			}
		}
		w.WriteMsg(a)
	})}
	go server.ActivateAndServe()
	t.Cleanup(func() { server.Shutdown() })
	return listener.LocalAddr().(*net.UDPAddr).Port, hits
}

func pinnedTarget(t *testing.T) (*httptest.Server, *atomic.Int32) {
	hits := new(atomic.Int32)
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1); w.WriteHeader(204) }))
	t.Cleanup(target.Close)
	return target, hits
}

// Preserve the audit's required behavior: a healthy B must work without a
// background main service. The Android network-binding callback is simulated.
func TestAuditIndependentURLTestShouldWorkWithoutProtectServer(t *testing.T) {
	target, hits := pinnedTarget(t)
	peer, peerHits := pinnedProxy(t, target.Listener.Addr().String())
	p := newPinnedControl()
	b := pinnedClient(t, peer, "ip", false, p, new(pinnedResolver))
	if _, err := UrlTest(b, target.URL, 1500); err != nil {
		t.Fatalf("healthy B cannot be tested without a running protect bridge: %v", err)
	}
	if hits.Load() != 2 || peerHits.Load() == 0 || p.calls.Load() == 0 {
		t.Fatal("B or its network control was bypassed")
	}
}
func TestPinnedBDoesNotFallBackToMainA(t *testing.T) {
	target, hits := pinnedTarget(t)
	aPeer, aHits := pinnedProxy(t, target.Listener.Addr().String())
	bPeer, bHits := pinnedProxy(t, target.Listener.Addr().String())
	ap, bp := newPinnedControl(), newPinnedControl()
	a := pinnedClient(t, aPeer, "ip", false, ap, new(pinnedResolver))
	mainInstance.Store(a)
	defer mainInstance.Store(nil)
	original := boxPlatformInterfaceInstance
	b := pinnedClient(t, bPeer, "ip", false, bp, new(pinnedResolver))
	if _, e := UrlTest(b, target.URL, 1500); e != nil {
		t.Fatal(e)
	}
	if aHits.Load() != 0 || ap.calls.Load() != 0 || bHits.Load() == 0 || hits.Load() != 2 {
		t.Fatal("explicit B or independent controller was bypassed")
	}
	if boxPlatformInterfaceInstance != original {
		t.Fatal("global platform overwritten")
	}
	if _, e := UrlTest(nil, target.URL, 1500); e != nil || aHits.Load() == 0 {
		t.Fatal("A control not healthy", e)
	}
	beforeA, beforeTarget := aHits.Load(), hits.Load()
	for _, mode := range []string{"bad-auth", "unreachable"} {
		bad := pinnedClient(t, bPeer, "ip", true, newPinnedControl(), new(pinnedResolver))
		if mode == "unreachable" {
			bPeer.Close()
			bad = pinnedClient(t, bPeer, "ip", false, newPinnedControl(), new(pinnedResolver))
		}
		if _, e := UrlTest(bad, target.URL, 1500); e == nil {
			t.Fatal("faulty B reported success")
		}
		if aHits.Load() != beforeA || hits.Load() != beforeTarget {
			t.Fatal("faulty B fell back to A")
		}
	}
	ap.valid.Store(false)
	goodPeer, _ := pinnedProxy(t, target.Listener.Addr().String())
	good := pinnedClient(t, goodPeer, "ip", false, newPinnedControl(), new(pinnedResolver))
	if _, e := UrlTest(good, target.URL, 1500); e != nil {
		t.Fatal("B depends on A network", e)
	}
}
func TestPinnedDNSAndDataControl(t *testing.T) {
	for _, mode := range []string{"local", "udp"} {
		t.Run(mode, func(t *testing.T) {
			target, hits := pinnedTarget(t)
			peer, _ := pinnedProxy(t, target.Listener.Addr().String())
			p := newPinnedControl()
			resolver := new(pinnedResolver)
			b := pinnedClient(t, peer, mode, false, p, resolver)
			if _, e := UrlTest(b, target.URL, 1500); e != nil {
				t.Fatal(e)
			}
			if hits.Load() != 2 || p.calls.Load() == 0 {
				t.Fatal("data skipped pinned control")
			}
			if mode == "local" && resolver.calls.Load() == 0 {
				t.Fatal("instance resolver not used")
			}
			if mode == "udp" && p.calls.Load() < 2 {
				t.Fatal("DNS socket skipped pinned control")
			}
			p.valid.Store(false)
			if _, e := UrlTest(b, target.URL, 1500); e == nil {
				t.Fatal("lost network reported success")
			}
		})
	}
}
func TestPinnedBindingFailureStopsBeforeNode(t *testing.T) {
	target, hits := pinnedTarget(t)
	peer, proxyHits := pinnedProxy(t, target.Listener.Addr().String())
	p := newPinnedControl()
	sentinel := errors.New("synthetic binding denied")
	p.bindError = sentinel
	b := pinnedClient(t, peer, "ip", false, p, new(pinnedResolver))
	if _, e := UrlTest(b, target.URL, 1500); e == nil || !errors.Is(e, sentinel) {
		t.Fatal("binding failure not propagated", e)
	}
	if proxyHits.Load() != 0 || hits.Load() != 0 {
		t.Fatal("traffic sent despite failed binding")
	}
}
func TestPinnedLocalDNSGuard(t *testing.T) {
	p := newPinnedControl()
	resolver := new(pinnedResolver)
	transport := newPlatformTransport(&testLocalDNSTransport{resolver, p}, "local", option.LocalDNSServerOptions{})
	p.valid.Store(false)
	q := new(dns.Msg)
	q.SetQuestion("node.invalid.", dns.TypeA)
	if _, e := transport.Exchange(context.Background(), q); e == nil || resolver.calls.Load() != 0 {
		t.Fatal("DNS proceeded on lost network", e)
	}
}

type pinnedMainPlatform struct {
	BoxPlatformInterface
	calls atomic.Int32
	fail  bool
}

func (p *pinnedMainPlatform) AutoDetectInterfaceControl(int32) error {
	p.calls.Add(1)
	if p.fail {
		return fmt.Errorf("synthetic main VPN protect denied")
	}
	return nil
}

func TestPinnedConstructorDoesNotReplaceMainVPNProtection(t *testing.T) {
	beforeBackground, beforeInterface := isBgProcess, intfBox
	defer func() { isBgProcess, intfBox = beforeBackground, beforeInterface }()
	isBgProcess = true
	mainControl := new(pinnedMainPlatform)
	intfBox = mainControl
	main, err := NewSingBoxInstance(`{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"direct"}],"route":{"auto_detect_interface":true,"final":"direct"}}`, new(pinnedResolver))
	if err != nil {
		t.Fatal(err)
	}
	defer main.Close()
	if err = main.Start(); err != nil {
		t.Fatal(err)
	}
	target, hits := pinnedTarget(t)
	peer, _ := pinnedProxy(t, target.Listener.Addr().String())
	testControl := newPinnedControl()
	test := pinnedClient(t, peer, "ip", false, testControl, new(pinnedResolver))
	if _, err = UrlTest(test, target.URL, 1500); err != nil || mainControl.calls.Load() != 0 {
		t.Fatal("test used main controller", err)
	}
	beforeTest := testControl.calls.Load()
	if _, err = UrlTest(main, target.URL, 1500); err != nil || mainControl.calls.Load() == 0 || testControl.calls.Load() != beforeTest {
		t.Fatal("main VPN controller replaced", err)
	}
	beforeHits := hits.Load()
	mainControl.fail = true
	if _, err = UrlTest(main, target.URL, 1500); err == nil || hits.Load() != beforeHits {
		t.Fatal("main protection failure was swallowed", err)
	}
}

func TestPinnedConcurrentInstancesAndClose(t *testing.T) {
	target, _ := pinnedTarget(t)
	peer, _ := pinnedProxy(t, target.Listener.Addr().String())
	var controls []*pinnedControl
	var clients []*BoxInstance
	for range 4 {
		p := newPinnedControl()
		controls = append(controls, p)
		clients = append(clients, pinnedClient(t, peer, "ip", false, p, new(pinnedResolver)))
	}
	results := make(chan error, len(clients))
	for _, client := range clients {
		go func(b *BoxInstance) { _, err := UrlTest(b, target.URL, 1500); results <- err }(client)
	}
	for range clients {
		if err := <-results; err != nil {
			t.Fatal(err)
		}
	}
	for _, p := range controls {
		if p.calls.Load() == 0 {
			t.Fatal("one instance bypassed its controller")
		}
	}
	controls[0].valid.Store(false)
	clients[0].Close()
	clients[0].Close()
	if _, err := UrlTest(clients[1], target.URL, 1500); err != nil {
		t.Fatal("closing another test affected this instance", err)
	}
}
