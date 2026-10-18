package libcore

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// The Android registry still contains the old DNS implementation, but 1.14.3's
// option decoder rejects its type. Translate routing uses into hijack-dns.
// References that cannot be represented as a route action fail explicitly.
func upgradeLegacyDNSOutbounds(data []byte) ([]byte, error) {
	var root map[string]any
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	if err := decoder.Decode(&root); err != nil {
		return nil, err
	}
	outbounds, ok := root["outbounds"].([]any)
	if !ok {
		return data, nil
	}
	tags := make(map[string]bool)
	var kept []any
	for index, value := range outbounds {
		outbound, ok := value.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("outbounds[%d]: must be an object", index)
		}
		if outbound["type"] != "dns" {
			kept = append(kept, value)
			continue
		}
		if len(outbound) > 2 {
			return nil, fmt.Errorf("outbounds[%d]: legacy DNS options cannot be safely migrated", index)
		}
		tag, _ := outbound["tag"].(string)
		if tag == "" {
			tag = fmt.Sprint(index)
		}
		if tags[tag] {
			return nil, fmt.Errorf("duplicate DNS outbound tag %q", tag)
		}
		tags[tag] = true
	}
	if len(tags) == 0 {
		return data, nil
	}
	for index, value := range outbounds {
		outbound := value.(map[string]any)
		if outbound["type"] == "dns" {
			continue
		}
		tag, _ := outbound["tag"].(string)
		if tag == "" {
			outbound["tag"] = fmt.Sprint(index)
		} else if tags[tag] {
			return nil, fmt.Errorf("duplicate outbound tag %q", tag)
		}
	}
	route, validRoute := root["route"].(map[string]any)
	if raw := root["route"]; raw != nil && !validRoute {
		return nil, fmt.Errorf("route: must be an object")
	}
	if route == nil {
		route = make(map[string]any)
		root["route"] = route
	}
	if raw := route["rules"]; raw != nil {
		if _, ok := raw.([]any); !ok {
			return nil, fmt.Errorf("route.rules: must be an array")
		}
	}
	var migrateRule func(map[string]any) error
	migrateRule = func(rule map[string]any) error {
		if outbound, _ := rule["outbound"].(string); tags[outbound] {
			if action, _ := rule["action"].(string); action != "" && action != "route" {
				return fmt.Errorf("legacy DNS outbound on non-routing action %q", action)
			}
			delete(rule, "outbound")
			rule["action"] = "hijack-dns"
		}
		if children, ok := rule["rules"].([]any); ok {
			for _, child := range children {
				if object, ok := child.(map[string]any); ok {
					if err := migrateRule(object); err != nil {
						return err
					}
				}
			}
		}
		return nil
	}
	if rules, ok := route["rules"].([]any); ok {
		for _, value := range rules {
			if rule, ok := value.(map[string]any); ok {
				if err := migrateRule(rule); err != nil {
					return nil, err
				}
			}
		}
	}
	final, _ := route["final"].(string)
	first := outbounds[0].(map[string]any)
	if tags[final] || final == "" && first["type"] == "dns" {
		rules, _ := route["rules"].([]any)
		route["rules"] = append(rules, map[string]any{"action": "hijack-dns"})
		delete(route, "final")
	}
	var check func(any) error
	check = func(value any) error {
		switch object := value.(type) {
		case map[string]any:
			for key, child := range object {
				switch key {
				case "detour", "download_detour", "default", "outbound", "outbounds":
					if tag, ok := child.(string); ok && tags[tag] {
						return fmt.Errorf("cannot migrate legacy DNS outbound reference in %s", key)
					}
					if list, ok := child.([]any); ok {
						for _, item := range list {
							if tag, ok := item.(string); ok && tags[tag] {
								return fmt.Errorf("cannot migrate legacy DNS outbound reference in %s", key)
							}
						}
					}
				}
				if err := check(child); err != nil {
					return err
				}
			}
		case []any:
			for _, child := range object {
				if err := check(child); err != nil {
					return err
				}
			}
		}
		return nil
	}
	root["outbounds"] = kept
	if err := check(root); err != nil {
		return nil, err
	}
	return json.Marshal(root)
}
