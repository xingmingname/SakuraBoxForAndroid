package libcore

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
)

// All compatibility work happens outside the upstream option parser. Unknown
// options are retained for its strict decoder; ambiguous migrations fail here.
func normalizeLegacyConfig(data []byte) ([]byte, error) {
	var root map[string]any
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	if err := decoder.Decode(&root); err != nil {
		return nil, err
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return nil, fmt.Errorf("$: expected one JSON configuration object")
	}
	if root == nil {
		return nil, fmt.Errorf("$: expected configuration object")
	}
	for _, key := range []string{"dns", "route"} {
		if value, present := root[key]; present && value != nil {
			if _, ok := value.(map[string]any); !ok {
				return nil, fmt.Errorf("%s: expected object", key)
			}
		}
	}
	inbounds, _ := root["inbounds"].([]any)
	for i, value := range inbounds {
		inbound, ok := value.(map[string]any)
		if !ok {
			continue
		}
		if inbound["type"] != "tun" {
			continue
		}
		for _, group := range [][]string{{"address", "inet4_address", "inet6_address"}, {"route_address", "inet4_route_address", "inet6_route_address"}, {"route_exclude_address", "inet4_route_exclude_address", "inet6_route_exclude_address"}} {
			var combined []any
			hasOld := false
			for _, old := range group[1:] {
				if v, present := inbound[old]; present {
					hasOld = true
					values, err := legacyStringList(v)
					if err != nil {
						return nil, fmt.Errorf("inbounds[%d].%s: %w", i, old, err)
					}
					combined = append(combined, values...)
				}
			}
			if !hasOld {
				continue
			}
			if _, present := inbound[group[0]]; present && len(combined) > 0 {
				return nil, fmt.Errorf("inbounds[%d].%s: conflicts with legacy split address fields", i, group[0])
			}
			if len(combined) > 0 {
				inbound[group[0]] = combined
			}
			for _, old := range group[1:] {
				delete(inbound, old)
			}
		}
		for _, key := range []string{"gso", "endpoint_independent_nat"} {
			if value, present := inbound[key]; present {
				if value != nil && value != false {
					return nil, fmt.Errorf("inbounds[%d].%s: removed; choose udp_mapping/udp_filtering explicitly for NAT; GSO has no equivalent", i, key)
				}
				delete(inbound, key)
			}
		}
	}
	dns, _ := root["dns"].(map[string]any)
	if dns != nil {
		if _, present := dns["fakeip"]; present {
			return nil, fmt.Errorf("dns.fakeip: removed; use a typed fakeip server with explicit ranges and DNS rules")
		}
		if v, present := dns["independent_cache"]; present {
			if _, ok := v.(bool); !ok && v != nil {
				return nil, fmt.Errorf("dns.independent_cache: expected boolean")
			}
			delete(dns, "independent_cache")
		} // 1.14 always isolates caches.
		servers, _ := dns["servers"].([]any)
		for i, v := range servers {
			server, ok := v.(map[string]any)
			if !ok {
				continue
			}
			if address, legacy := server["address"].(string); legacy {
				if _, present := server["server"]; present {
					return nil, fmt.Errorf("dns.servers[%d].address: conflicts with server", i)
				}
				if _, old := server["address_resolver"]; old {
					if _, modern := server["domain_resolver"]; modern {
						return nil, fmt.Errorf("dns.servers[%d].address_resolver: conflicts with domain_resolver", i)
					}
				}
				if kind, _ := server["type"].(string); kind != "" && kind != "legacy" {
					return nil, fmt.Errorf("dns.servers[%d].address: conflicts with typed server", i)
				}
				if err := migrateLegacyDNSServer(server, address); err != nil {
					return nil, fmt.Errorf("dns.servers[%d]: %w", i, err)
				}
			}
		}
		rules, _ := dns["rules"].([]any)
		// The only unconditional legacy outbound rule can be represented exactly as
		// the default domain resolver. More complex conditional ordering cannot.
		if len(rules) == 1 {
			if rule, ok := rules[0].(map[string]any); ok {
				outbound, present := rule["outbound"]
				if present {
					list, err := legacyStringList(outbound)
					if err != nil {
						return nil, fmt.Errorf("dns.rules[0].outbound: %w", err)
					}
					safe := len(list) == 1 && list[0] == "any"
					for key := range rule {
						if !containsString([]string{"outbound", "server", "strategy", "disable_cache", "rewrite_ttl", "client_subnet", "action"}, key) {
							safe = false
						}
					}
					action, _ := rule["action"].(string)
					if action != "" && action != "route" {
						safe = false
					}
					server, _ := rule["server"].(string)
					if server == "" {
						safe = false
					}
					if safe {
						route, _ := root["route"].(map[string]any)
						if route == nil {
							route = map[string]any{}
							root["route"] = route
						}
						if _, explicit := route["default_domain_resolver"]; !explicit {
							resolver := map[string]any{}
							for key, value := range rule {
								if key != "outbound" && key != "action" {
									resolver[key] = value
								}
							}
							route["default_domain_resolver"] = resolver
						}
						dns["rules"] = []any{}
					}
				}
			}
		}
	}
	// Dialer domain_strategy is not an inbound domain_strategy. Do not touch
	// inbound migration or the strategy of route.resolve/domain_resolver.
	for _, section := range []string{"outbounds", "endpoints"} {
		values, _ := root[section].([]any)
		for i, v := range values {
			dialer, ok := v.(map[string]any)
			if !ok {
				continue
			}
			raw, present := dialer["domain_strategy"]
			if !present {
				continue
			}
			strategy, ok := raw.(string)
			if !ok && raw != nil {
				return nil, fmt.Errorf("%s[%d].domain_strategy: expected string", section, i)
			}
			delete(dialer, "domain_strategy")
			if strategy == "" || strategy == "as_is" {
				continue
			}
			resolver := map[string]any{}
			switch existing := dialer["domain_resolver"].(type) {
			case string:
				resolver["server"] = existing
			case map[string]any:
				for k, v := range existing {
					resolver[k] = v
				}
			default:
				route, _ := root["route"].(map[string]any)
				switch fallback := route["default_domain_resolver"].(type) {
				case string:
					resolver["server"] = fallback
				case map[string]any:
					for k, v := range fallback {
						resolver[k] = v
					}
				}
			}
			if resolver["server"] == nil {
				return nil, fmt.Errorf("%s[%d].domain_strategy: set domain_resolver.server explicitly before migrating this strategy", section, i)
			}
			if current, ok := resolver["strategy"].(string); ok && current != "" && current != "as_is" && current != strategy {
				return nil, fmt.Errorf("%s[%d].domain_strategy: conflicts with domain_resolver.strategy", section, i)
			}
			resolver["strategy"] = strategy
			dialer["domain_resolver"] = resolver
		}
	}
	route, _ := root["route"].(map[string]any)
	sets, _ := route["rule_set"].([]any)
	for i, v := range sets {
		set, ok := v.(map[string]any)
		if !ok {
			continue
		}
		if detour, present := set["download_detour"]; present {
			if _, conflict := set["http_client"]; conflict {
				return nil, fmt.Errorf("route.rule_set[%d].download_detour: conflicts with http_client", i)
			}
			if detour != "" && detour != nil {
				set["http_client"] = map[string]any{"detour": detour}
			}
			delete(set, "download_detour")
		}
	}
	return json.Marshal(root)
}

func containsString(list []string, value string) bool {
	for _, s := range list {
		if s == value {
			return true
		}
	}
	return false
}
func legacyStringList(value any) ([]any, error) {
	switch v := value.(type) {
	case nil:
		return nil, nil
	case string:
		if v == "" {
			return nil, nil
		}
		return []any{v}, nil
	case []any:
		for _, x := range v {
			if _, ok := x.(string); !ok {
				return nil, fmt.Errorf("expected string list")
			}
		}
		return v, nil
	default:
		return nil, fmt.Errorf("expected string/list")
	}
}

func migrateLegacyDNSServer(server map[string]any, address string) error {
	for _, key := range []string{"strategy", "client_subnet", "address_strategy", "address_fallback_delay"} {
		if v, ok := server[key]; ok && !inactiveLegacyValue(v) {
			return fmt.Errorf("%s: cannot safely migrate legacy per-server policy; use DNS query rules/domain_resolver", key)
		}
		delete(server, key)
	}
	if address == "local" {
		server["type"] = "local"
	} else {
		if !strings.Contains(address, "://") {
			if _, err := netip.ParseAddr(address); err != nil {
				return fmt.Errorf("address: ambiguous legacy address; use typed server")
			}
			address = "udp://" + net.JoinHostPort(address, "53")
		}
		u, err := url.Parse(address)
		if err != nil || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return fmt.Errorf("address: invalid or unsupported legacy DNS URL")
		}
		kind := u.Scheme
		if !containsString([]string{"udp", "tcp", "tls", "https", "quic", "h3"}, kind) {
			return fmt.Errorf("address: unsupported legacy DNS type; select a typed server explicitly")
		}
		server["type"] = kind
		server["server"] = u.Hostname()
		if u.Port() != "" {
			port, err := strconv.Atoi(u.Port())
			if err != nil || port < 1 || port > 65535 {
				return fmt.Errorf("address: invalid DNS port")
			}
			server["server_port"] = port
		}
		if u.EscapedPath() != "" && u.EscapedPath() != "/" {
			if kind != "https" && kind != "h3" {
				return fmt.Errorf("address: path only supported for HTTPS/H3")
			}
			server["path"] = u.EscapedPath()
		}
		// Legacy remote DNS dialed the default outbound. Require the user to name
		// that route explicitly rather than silently changing it to the new direct dialer.
		if detour, _ := server["detour"].(string); detour == "" {
			return fmt.Errorf("detour: legacy remote default route is ambiguous; specify an explicit outbound")
		}
	}
	if resolver, present := server["address_resolver"]; present {
		server["domain_resolver"] = resolver
		delete(server, "address_resolver")
	}
	delete(server, "address")
	return nil
}

func inactiveLegacyValue(value any) bool {
	switch v := value.(type) {
	case nil:
		return true
	case string:
		return v == "" || v == "as_is"
	default:
		return false
	}
}

func validateRemovedConfig(data []byte) error {
	var root any
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	if err := decoder.Decode(&root); err != nil {
		return err
	}
	var visit func(any, string) error
	visit = func(value any, path string) error {
		switch v := value.(type) {
		case map[string]any:
			for key, child := range v {
				next := path + "." + key
				isDNSRule := strings.HasPrefix(path, "$.dns.rules[")
				if isDNSRule && (key == "strategy" || key == "outbound") {
					return fmt.Errorf("%s: legacy DNS action/matcher cannot be safely migrated; use query_type/ip_version and domain_resolver", next)
				}
				if isDNSRule && (key == "rule_set_ip_cidr_accept_empty" || key == "rule_set_ipcidr_match_source") {
					return fmt.Errorf("%s: legacy rule-set match option; use response matching / rule_set_ip_cidr_match_source", next)
				}
				if (strings.HasPrefix(path, "$.route.rules[") || isDNSRule || path == "$.route") && containsString([]string{"geoip", "geosite", "source_geoip"}, key) {
					return fmt.Errorf("%s: database fields removed; use a standard rule_set (Geo shortcuts are internal paths only)", next)
				}
				if strings.HasPrefix(path, "$.outbounds[") && (key == "override_address" || key == "override_port") {
					return fmt.Errorf("%s: removed direct outbound override; use route-options on the intended matching traffic", next)
				}
				if strings.HasPrefix(path, "$.dns.servers[") && key == "strategy" {
					return fmt.Errorf("%s: removed per-server strategy; use query_type/ip_version policies", next)
				}
				if err := visit(child, next); err != nil {
					return err
				}
			}
		case []any:
			for i, child := range v {
				if err := visit(child, fmt.Sprintf("%s[%d]", path, i)); err != nil {
					return err
				}
			}
		}
		return nil
	}
	return visit(root, "$")
}
