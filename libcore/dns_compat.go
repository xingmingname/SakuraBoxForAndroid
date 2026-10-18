package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"

	"github.com/sagernet/sing-box/option"
)

// Overrides and complete JSON pass this same boundary. Only a provably plain
// direct outbound can be replaced by the transport's built-in direct dialer.
// Configured direct outbounds and every proxy detour are left intact.
func upgradeEmptyDirectDNSDetours(data []byte) ([]byte, error) {
	var root map[string]json.RawMessage
	if err := json.Unmarshal(data, &root); err != nil {
		return nil, err
	}
	var outbounds []map[string]json.RawMessage
	if raw := root["outbounds"]; len(raw) > 0 {
		if err := json.Unmarshal(raw, &outbounds); err != nil {
			return nil, fmt.Errorf("outbounds: %w", err)
		}
	}
	plain := make(map[string]bool)
	for _, outbound := range outbounds {
		var kind, tag string
		_ = json.Unmarshal(outbound["type"], &kind)
		_ = json.Unmarshal(outbound["tag"], &tag)
		if kind == "direct" && tag != "" {
			fields := make(map[string]json.RawMessage)
			for key, value := range outbound {
				if key != "type" && key != "tag" {
					fields[key] = value
				}
			}
			encoded, _ := json.Marshal(fields)
			var direct option.DirectOutboundOptions
			if direct.UnmarshalJSONContext(context.Background(), encoded) != nil || direct.ProxyProtocol != 0 {
				continue
			}
			// Match direct.NewOutbound's actual IsEmpty calculation, including
			// explicit zero-valued fields from custom JSON serializers.
			direct.UDPFragmentDefault = true
			plain[tag] = reflect.DeepEqual(direct.DialerOptions, option.DialerOptions{
				AbstractDialerOptions: option.AbstractDialerOptions{UDPFragmentDefault: true},
			})
		}
	}
	var dnsOptions map[string]json.RawMessage
	if raw := root["dns"]; len(raw) > 0 {
		if err := json.Unmarshal(raw, &dnsOptions); err != nil {
			return nil, fmt.Errorf("dns: %w", err)
		}
	}
	var servers []map[string]json.RawMessage
	if raw := dnsOptions["servers"]; len(raw) > 0 {
		if err := json.Unmarshal(raw, &servers); err != nil {
			return nil, fmt.Errorf("dns.servers: %w", err)
		}
	}
	changed := false
	for _, server := range servers {
		var kind, detour string
		_ = json.Unmarshal(server["type"], &kind)
		_ = json.Unmarshal(server["detour"], &detour)
		switch kind {
		case "udp", "tcp", "tls", "https", "quic", "h3", "local", "dhcp":
			if plain[detour] {
				delete(server, "detour")
				changed = true
			}
		}
	}
	if !changed {
		return data, nil
	}
	dnsOptions["servers"], _ = json.Marshal(servers)
	root["dns"], _ = json.Marshal(dnsOptions)
	return json.Marshal(root)
}
