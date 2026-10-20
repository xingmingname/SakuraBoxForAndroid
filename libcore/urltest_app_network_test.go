package libcore

import (
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"testing"

	"github.com/miekg/dns"
)

type appNetworkResolver struct {
	*pinnedResolver
	handle int64
}

func (*appNetworkResolver) Raw() bool              { return true }
func (p *appNetworkResolver) NetworkHandle() int64 { return p.handle }

// Network handles and socket binding are simulated here; HTTP CONNECT and the
// test request execute against real local peers through the actual core.
func TestURLTestAppNetworkDNSHandleAndDataControl(t *testing.T) {
	previous := rawQueryFunc
	defer func() { rawQueryFunc = previous }()
	for index, mode := range []string{"no-vpn", "own-vpn-physical", "external-included", "external-excluded"} {
		t.Run(mode, func(t *testing.T) {
			target, targetHits := pinnedTarget(t)
			peer, peerHits := pinnedProxy(t, target.Listener.Addr().String())
			u, _ := url.Parse(peer.URL)
			_, textPort, _ := net.SplitHostPort(u.Host)
			port, _ := strconv.Atoi(textPort)
			handle := int64(700 + index)
			dnsCalls := 0
			rawQueryFunc = func(actual int64, request []byte) ([]byte, error) {
				if actual != handle {
					return nil, fmt.Errorf("DNS used a different application network handle")
				}
				dnsCalls++
				q := new(dns.Msg)
				if err := q.Unpack(request); err != nil {
					return nil, err
				}
				answer := new(dns.Msg)
				answer.SetReply(q)
				for _, question := range q.Question {
					if question.Qtype == dns.TypeA {
						answer.Answer = append(answer.Answer, &dns.A{Hdr: dns.RR_Header{Name: question.Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 1}, A: net.IPv4(127, 0, 0, 1)})
					}
				}
				return answer.Pack()
			}
			config, err := json.Marshal(map[string]any{
				"log": map[string]any{"disabled": true},
				"outbounds": []any{map[string]any{"type": "http", "tag": "B", "server": "node.invalid", "server_port": port,
					"username": "audit-user", "password": "synthetic-only", "domain_resolver": map[string]any{"server": "test-dns", "strategy": "ipv4_only"}}},
				"dns":   map[string]any{"servers": []any{map[string]any{"type": "local", "tag": "test-dns"}}, "final": "test-dns"},
				"route": map[string]any{"auto_detect_interface": true, "final": "B"},
			})
			if err != nil {
				t.Fatal(err)
			}
			control := newPinnedControl()
			before := boxPlatformInterfaceInstance
			box, err := NewTestSingBoxInstance(string(config), &appNetworkResolver{new(pinnedResolver), handle}, control)
			if err != nil {
				t.Fatal(err)
			}
			defer box.Close()
			if err = box.Start(); err != nil {
				t.Fatal(err)
			}
			if _, err = UrlTest(box, target.URL, 1500); err != nil {
				t.Fatal(err)
			}
			if dnsCalls == 0 || control.calls.Load() == 0 || peerHits.Load() == 0 || targetHits.Load() != 2 {
				t.Fatal("DNS, B, or the instance socket controller was skipped")
			}
			if boxPlatformInterfaceInstance != before {
				t.Fatal("Main VPN platform was replaced")
			}
		})
	}
}
