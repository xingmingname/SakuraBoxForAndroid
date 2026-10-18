package libcore

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func f01Config() map[string]any {
	return map[string]any{
		"log":       map[string]any{"disabled": true},
		"route":     map[string]any{"auto_detect_interface": true, "final": "direct"},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}},
	}
}

func f01New(t *testing.T, config map[string]any, control *pinnedControl) (*BoxInstance, error) {
	t.Helper()
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	return NewTestSingBoxInstance(string(raw), new(pinnedResolver), control)
}

// Real remote rule-set creation exercises named, default and inline HTTP
// clients. Conflicts must fail during construction, not after an HTTP request.
func TestF01HTTPClientIsolation(t *testing.T) {
	for _, mode := range []string{"named", "default", "inline"} {
		for _, scenario := range []string{"conflict", "bound", "binding-denied"} {
			t.Run(mode+"/"+scenario, func(t *testing.T) {
				var hits atomic.Int32
				server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					hits.Add(1)
					w.Header().Set("Content-Type", "application/json")
					fmt.Fprint(w, `{"version":3,"rules":[{"domain":["regression.invalid"]}]}`)
				}))
				defer server.Close()
				client := map[string]any{"version": 1}
				if scenario == "conflict" {
					client["inet4_bind_address"] = "0.0.0.0"
				}
				config := f01Config()
				route := config["route"].(map[string]any)
				rules := map[string]any{"type": "remote", "tag": "rules", "format": "source", "url": server.URL}
				path := "http_clients[0].inet4_bind_address"
				if mode == "inline" {
					rules["http_client"] = client
					path = "route.rule_set[0].http_client.inet4_bind_address"
				} else {
					client["tag"] = "rules-client"
					config["http_clients"] = []any{client}
					if mode == "default" {
						route["default_http_client"] = "rules-client"
					} else {
						rules["http_client"] = "rules-client"
					}
				}
				route["rule_set"] = []any{rules}
				control := newPinnedControl()
				if scenario != "bound" {
					control.bindError = errors.New("synthetic physical binding denied")
				}
				b, err := f01New(t, config, control)
				if b != nil {
					defer b.Close()
				}
				if scenario == "conflict" {
					if err == nil || b != nil || !strings.Contains(err.Error(), path) {
						t.Fatalf("expected early rejection at %s: %v", path, err)
					}
					if hits.Load() != 0 || control.calls.Load() != 0 {
						t.Fatal("conflicting configuration reached socket creation or HTTP peer")
					}
					return
				}
				if err != nil {
					t.Fatal(err)
				}
				err = b.Start()
				if scenario == "bound" {
					if err != nil || hits.Load() == 0 || control.calls.Load() == 0 {
						t.Fatalf("safe rule-set download failed: %v, hits=%d binds=%d", err, hits.Load(), control.calls.Load())
					}
				} else if err == nil || !strings.Contains(err.Error(), "synthetic physical binding denied") || hits.Load() != 0 || control.calls.Load() == 0 {
					t.Fatalf("binding failure lost: %v, hits=%d binds=%d", err, hits.Load(), control.calls.Load())
				}
			})
		}
	}
}

func TestF01NTPIsolation(t *testing.T) {
	for _, scenario := range []string{"conflict", "bound", "binding-denied"} {
		t.Run(scenario, func(t *testing.T) {
			conn, err := net.ListenPacket("udp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			var hits atomic.Int32
			done := make(chan struct{})
			go func() {
				defer close(done)
				conn.SetReadDeadline(time.Now().Add(time.Second))
				buffer := make([]byte, 512)
				n, addr, readErr := conn.ReadFrom(buffer)
				if readErr == nil && n > 0 {
					hits.Add(1)
					// Deliberately invalid time response; never change system time.
					conn.WriteTo(make([]byte, 48), addr)
				}
			}()
			_, portText, _ := net.SplitHostPort(conn.LocalAddr().String())
			port, _ := strconv.Atoi(portText)
			ntp := map[string]any{"enabled": true, "write_to_system": false, "server": "127.0.0.1", "server_port": port}
			if scenario == "conflict" {
				ntp["inet4_bind_address"] = "0.0.0.0"
			}
			config := f01Config()
			config["ntp"] = ntp
			control := newPinnedControl()
			if scenario != "bound" {
				control.bindError = errors.New("synthetic physical binding denied")
			}
			b, err := f01New(t, config, control)
			if b != nil {
				defer b.Close()
			}
			if scenario == "conflict" {
				if err == nil || b != nil || !strings.Contains(err.Error(), "ntp.inet4_bind_address") {
					t.Fatalf("expected early NTP rejection: %v", err)
				}
			} else {
				if err != nil {
					t.Fatal(err)
				}
				if err = b.Start(); err != nil {
					t.Fatal(err)
				}
			}
			// Wait for the receiver's deadline even on rejection: no unobserved
			// asynchronous packet may be mistaken for a passing isolation check.
			<-done
			if scenario == "bound" {
				if hits.Load() == 0 || control.calls.Load() == 0 {
					t.Fatalf("safe NTP not bound: packets=%d binds=%d", hits.Load(), control.calls.Load())
				}
			} else if hits.Load() != 0 || (scenario == "conflict" && control.calls.Load() != 0) || (scenario == "binding-denied" && control.calls.Load() == 0) {
				t.Fatalf("NTP isolation failed: packets=%d binds=%d", hits.Load(), control.calls.Load())
			}
		})
	}
}

func TestF01ConflictCoverage(t *testing.T) {
	for _, entry := range []string{"http_clients", "ntp", "inline-http", "hy2-realm-http", "dns", "outbound", "endpoint", "legacy-wireguard", "direct-action", "inbound-handshake"} {
		for key, value := range map[string]any{"bind_interface": "other", "inet4_bind_address": "0.0.0.0", "inet6_bind_address": "::", "routing_mark": 123, "protect_path": "synthetic", "netns": "other"} {
			t.Run(entry+"/"+key, func(t *testing.T) {
				config := f01Config()
				payload := map[string]any{key: value}
				path := ""
				switch entry {
				case "http_clients":
					payload["tag"] = "client"
					config[entry] = []any{payload}
					path = "http_clients[0]." + key
				case "ntp":
					payload["server"] = "127.0.0.1"
					config[entry] = payload
					path = "ntp." + key
				case "inline-http":
					config["route"].(map[string]any)["rule_set"] = []any{map[string]any{"type": "remote", "tag": "rules", "url": "http://127.0.0.1/rules.json", "http_client": payload}}
					path = "route.rule_set[0].http_client." + key
				case "dns":
					payload["type"], payload["server"] = "udp", "127.0.0.1"
					config["dns"] = map[string]any{"servers": []any{payload}}
					path = "dns.servers[0]." + key
				case "hy2-realm-http":
					config["outbounds"] = []any{map[string]any{"type": "hysteria2", "tag": "direct", "server": "127.0.0.1", "server_port": 443, "realm": map[string]any{"server_url": "http://127.0.0.1", "realm_id": "synthetic", "stun_servers": []string{"127.0.0.1:3478"}, "http_client": payload}}}
					path = "outbounds[0].realm.http_client." + key
				case "outbound":
					payload["type"], payload["tag"] = "direct", "direct"
					config["outbounds"] = []any{payload}
					path = "outbounds[0]." + key
				case "endpoint", "legacy-wireguard":
					payload["type"], payload["tag"] = "wireguard", "wg"
					payload["private_key"] = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
					if entry == "endpoint" {
						payload["address"] = []string{"10.0.0.2/32"}
						config["endpoints"] = []any{payload}
					} else {
						payload["local_address"] = []string{"10.0.0.2/32"}
						config["outbounds"] = []any{payload}
					}
					path = "endpoints[0]." + key
				case "direct-action":
					payload["action"] = "direct"
					config["route"].(map[string]any)["rules"] = []any{payload}
					path = "route.rules[0]." + key
				case "inbound-handshake":
					payload["server"], payload["server_port"] = "127.0.0.1", 443
					config["inbounds"] = []any{map[string]any{"type": "http", "tls": map[string]any{"enabled": true, "reality": map[string]any{"enabled": true, "handshake": payload}}}}
					path = "inbounds[0].tls.reality.handshake." + key
				}
				control := newPinnedControl()
				b, err := f01New(t, config, control)
				if b != nil {
					b.Close()
				}
				if err == nil || !strings.Contains(err.Error(), "pinned network with "+path) || b != nil || control.calls.Load() != 0 {
					t.Fatalf("expected isolation rejection at %s before core creation: %v", path, err)
				}
			})
		}
	}
}

func TestF01OpaqueHeadersAndEmptyBindings(t *testing.T) {
	config := f01Config()
	config["http_clients"] = []any{map[string]any{
		"tag": "safe", "bind_interface": "", "inet4_bind_address": nil, "inet6_bind_address": nil,
		"protect_path": "", "routing_mark": 0, "netns": "",
		"headers": map[string]any{"bind_interface": "legitimate-header", "inet4_bind_address": "header-value", "netns": "header-value"},
	}}
	b, err := f01New(t, config, newPinnedControl())
	if err != nil {
		t.Fatal("data keys or empty bindings were treated as dialer overrides:", err)
	}
	b.Close()
}

func TestF01MainVPNConfigPolicyUnchanged(t *testing.T) {
	config := f01Config()
	config["http_clients"] = []any{map[string]any{"tag": "ordinary", "inet4_bind_address": "0.0.0.0"}}
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	// The ordinary constructor must not acquire the test-only policy. Do not
	// Start: this verifies construction without making any network requests.
	b, err := NewSingBoxInstance(string(raw), new(pinnedResolver))
	if err != nil {
		t.Fatal("test policy changed the main constructor:", err)
	}
	b.Close()
}

func TestF01UnsupportedProvidersFailBeforeNetwork(t *testing.T) {
	for _, entry := range []string{"services", "certificate_providers"} {
		t.Run(entry, func(t *testing.T) {
			config := f01Config()
			kind := "api"
			if entry == "certificate_providers" {
				kind = "acme"
			}
			config[entry] = []any{map[string]any{"type": kind, "tag": "unsupported"}}
			control := newPinnedControl()
			b, err := f01New(t, config, control)
			if b != nil {
				b.Close()
			}
			if b != nil || err == nil || !strings.Contains(err.Error(), "decode config:") || !strings.Contains(err.Error(), "unknown") || control.calls.Load() != 0 {
				t.Fatalf("unregistered provider was not strictly rejected: %v", err)
			}
		})
	}
}
