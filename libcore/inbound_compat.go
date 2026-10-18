package libcore

import (
	"encoding/json"
	"fmt"

	"github.com/sagernet/sing-box/adapter"
	R "github.com/sagernet/sing-box/route/rule"
	"github.com/sagernet/sing/common/json/badoption"
)

// This wrapper-only metadata is removed before sing-box decodes the configuration.
// The 1.14.3 router still implements destination override, but its JSON sniff
// action no longer exposes it. Apply the flag to the constructed action before
// Start, without relaxing sing-box's legacy inbound validation.
const sniffOverrideMetadata = "_neko_sniff_override_rules"

// MigrateConfig is shared by ConfigBuilder (after all JSON overrides) and the
// native configuration entry point, including complete user configurations.
func MigrateConfig(config string) (string, error) {
	data, err := normalizeLegacyConfig([]byte(config))
	if err != nil {
		return "", err
	}
	data, err = upgradeLegacyWireGuardOutbounds(data)
	if err != nil {
		return "", err
	}
	data, err = upgradeEmptyDirectDNSDetours(data)
	if err != nil {
		return "", err
	}
	data, err = upgradeLegacyDNSOutbounds(data)
	if err != nil {
		return "", err
	}
	data, err = upgradeLegacyInbounds(data)
	if err == nil {
		err = validateRemovedConfig(data)
	}
	return string(data), err
}

func upgradeLegacyInbounds(data []byte) ([]byte, error) {
	var root map[string]json.RawMessage
	if err := json.Unmarshal(data, &root); err != nil {
		return nil, err
	}
	if root == nil {
		return nil, fmt.Errorf("configuration must be a JSON object")
	}
	var inbounds []map[string]json.RawMessage
	if raw := root["inbounds"]; len(raw) > 0 {
		if err := json.Unmarshal(raw, &inbounds); err != nil {
			return nil, fmt.Errorf("inbounds: %w", err)
		}
	}
	var route map[string]json.RawMessage
	if raw := root["route"]; len(raw) > 0 {
		if err := json.Unmarshal(raw, &route); err != nil {
			return nil, fmt.Errorf("route: %w", err)
		}
	}
	if route == nil {
		route = make(map[string]json.RawMessage)
	}
	var originalRules []json.RawMessage
	if raw := route["rules"]; len(raw) > 0 {
		if err := json.Unmarshal(raw, &originalRules); err != nil {
			return nil, fmt.Errorf("route.rules: %w", err)
		}
	}
	var previousOverrides []int
	if raw := root[sniffOverrideMetadata]; len(raw) > 0 {
		if err := json.Unmarshal(raw, &previousOverrides); err != nil {
			return nil, fmt.Errorf("%s: %w", sniffOverrideMetadata, err)
		}
		if err := validateSniffOverrideIndices(originalRules, previousOverrides); err != nil {
			return nil, err
		}
	}
	tags := make(map[string]bool)
	for index, inbound := range inbounds {
		var tag string
		_ = json.Unmarshal(inbound["tag"], &tag)
		if tag == "" {
			tag = fmt.Sprint(index)
		}
		{
			if tags[tag] {
				return nil, fmt.Errorf("duplicate inbound tag %q", tag)
			}
			tags[tag] = true
		}
	}
	var migratedRules []json.RawMessage
	var overrides []int
	changed := false
	for index, inbound := range inbounds {
		if inbound == nil {
			return nil, fmt.Errorf("inbounds[%d]: must be an object", index)
		}
		legacy := false
		for _, field := range []string{"sniff", "sniff_override_destination", "sniff_timeout", "domain_strategy", "udp_disable_domain_unmapping"} {
			if _, exists := inbound[field]; exists {
				legacy = true
			}
		}
		if !legacy {
			continue
		}
		changed = true
		if raw := inbound["sniff_timeout"]; len(raw) > 0 && string(raw) != "null" {
			var timeout badoption.Duration
			if err := timeout.UnmarshalJSON(raw); err != nil {
				return nil, fmt.Errorf("inbounds[%d].sniff_timeout: %w", index, err)
			}
		}
		readBool := func(field string) (bool, error) {
			var value bool
			if raw := inbound[field]; len(raw) > 0 {
				if err := json.Unmarshal(raw, &value); err != nil {
					return false, fmt.Errorf("inbounds[%d].%s: must be a boolean", index, field)
				}
			}
			return value, nil
		}
		sniff, err := readBool("sniff")
		if err != nil {
			return nil, err
		}
		override, err := readBool("sniff_override_destination")
		if err != nil {
			return nil, err
		}
		unmapping, err := readBool("udp_disable_domain_unmapping")
		if err != nil {
			return nil, err
		}
		var strategy string
		if raw := inbound["domain_strategy"]; len(raw) > 0 {
			if err := json.Unmarshal(raw, &strategy); err != nil {
				return nil, fmt.Errorf("inbounds[%d].domain_strategy: must be a string", index)
			}
		}
		switch strategy {
		case "", "as_is", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only":
		default:
			return nil, fmt.Errorf("inbounds[%d].domain_strategy: unsupported strategy %q", index, strategy)
		}
		var tag string
		_ = json.Unmarshal(inbound["tag"], &tag)
		// sing-box assigns untagged inbounds their decimal index. Retain that
		// identity so existing user rules matching it continue to work.
		if tag == "" {
			tag = fmt.Sprint(index)
		}
		addRule := func(action string, extra map[string]json.RawMessage) {
			rule := map[string]json.RawMessage{}
			rule["inbound"], _ = json.Marshal([]string{tag})
			rule["action"], _ = json.Marshal(action)
			for key, value := range extra {
				rule[key] = value
			}
			encoded, _ := json.Marshal(rule)
			migratedRules = append(migratedRules, encoded)
		}
		// Legacy processing was sniff -> resolve -> UDP route options, before
		// every user rule (including DNS hijack). Retain that order.
		if sniff {
			if override {
				overrides = append(overrides, len(migratedRules))
			}
			extra := map[string]json.RawMessage{}
			if timeout := inbound["sniff_timeout"]; len(timeout) > 0 && string(timeout) != "null" {
				extra["timeout"] = timeout
			}
			addRule("sniff", extra)
		}
		if strategy != "" && strategy != "as_is" {
			encoded, _ := json.Marshal(strategy)
			addRule("resolve", map[string]json.RawMessage{"strategy": encoded})
		}
		if unmapping {
			addRule("route-options", map[string]json.RawMessage{"udp_disable_domain_unmapping": json.RawMessage("true")})
		}
		for _, field := range []string{"sniff", "sniff_override_destination", "sniff_timeout", "domain_strategy", "udp_disable_domain_unmapping"} {
			delete(inbound, field)
		}
	}
	if !changed {
		return data, nil
	}
	for _, index := range previousOverrides {
		overrides = append(overrides, index+len(migratedRules))
	}
	if len(migratedRules) > 0 {
		route["rules"], _ = json.Marshal(append(migratedRules, originalRules...))
		root["route"], _ = json.Marshal(route)
	}
	root["inbounds"], _ = json.Marshal(inbounds)
	if len(overrides) > 0 {
		root[sniffOverrideMetadata], _ = json.Marshal(overrides)
	}
	return json.Marshal(root)
}

func validateSniffOverrideIndices(rules []json.RawMessage, indices []int) error {
	for _, index := range indices {
		if index < 0 || index >= len(rules) {
			return fmt.Errorf("%s: rule index %d is out of range", sniffOverrideMetadata, index)
		}
		var rule struct {
			Action string `json:"action"`
			Type   string `json:"type"`
		}
		if json.Unmarshal(rules[index], &rule) != nil || rule.Action != "sniff" || rule.Type == "logical" {
			return fmt.Errorf("%s: rule %d must be a top-level sniff action", sniffOverrideMetadata, index)
		}
	}
	return nil
}

func prepareConfig(config string) ([]byte, []int, error) {
	migrated, err := MigrateConfig(config)
	if err != nil {
		return nil, nil, err
	}
	var root map[string]json.RawMessage
	if err = json.Unmarshal([]byte(migrated), &root); err != nil {
		return nil, nil, err
	}
	var indices []int
	if raw := root[sniffOverrideMetadata]; len(raw) > 0 {
		if err = json.Unmarshal(raw, &indices); err != nil {
			return nil, nil, err
		}
		delete(root, sniffOverrideMetadata)
	}
	data, err := json.Marshal(root)
	return data, indices, err
}

func applySniffOverrides(rules []adapter.Rule, indices []int) error {
	for _, index := range indices {
		if index < 0 || index >= len(rules) {
			return fmt.Errorf("sniff override: constructed rule %d is missing", index)
		}
		action, ok := rules[index].Action().(*R.RuleActionSniff)
		if !ok {
			return fmt.Errorf("sniff override: constructed rule %d is not a sniff action", index)
		}
		action.OverrideDestination = true
	}
	return nil
}
