package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

// ExportPortableConfig targets the official Android client. Geo data is embedded
// as inline rules; destination-override sniff has no upstream JSON representation.
func ExportPortableConfig(config string) (string, error) {
	data, overrides, err := prepareConfig(config)
	if err != nil {
		return "", err
	}
	if len(overrides) > 0 {
		return "", fmt.Errorf("$._neko_sniff_override_rules: sniff destination override is not portable; use NekoBox internal export or explicitly select sniff-only before exporting")
	}
	var root map[string]json.RawMessage
	if err = json.Unmarshal(data, &root); err != nil {
		return "", err
	}
	if err = applyAndroidSFAExportDefaults(root); err != nil {
		return "", err
	}
	var route map[string]json.RawMessage
	_ = json.Unmarshal(root["route"], &route)
	var sets []map[string]json.RawMessage
	_ = json.Unmarshal(route["rule_set"], &sets)
	for i, set := range sets {
		var path string
		_ = json.Unmarshal(set["path"], &path)
		var rules []option.HeadlessRule
		switch {
		case strings.HasPrefix(path, "geoip:"):
			if nekoutils.GetGeoIPHeadlessRules == nil {
				return "", fmt.Errorf("route.rule_set[%d].path: GeoIP asset provider unavailable", i)
			}
			rules, err = nekoutils.GetGeoIPHeadlessRules(strings.TrimPrefix(path, "geoip:"))
		case strings.HasPrefix(path, "geosite:"):
			if nekoutils.GetGeoSiteHeadlessRules == nil {
				return "", fmt.Errorf("route.rule_set[%d].path: Geosite asset provider unavailable", i)
			}
			rules, err = nekoutils.GetGeoSiteHeadlessRules(strings.TrimPrefix(path, "geosite:"))
		default:
			continue
		}
		if err != nil {
			return "", fmt.Errorf("route.rule_set[%d].path: cannot embed Geo data: %w", i, err)
		}
		if len(rules) == 0 {
			return "", fmt.Errorf("route.rule_set[%d].path: empty Geo asset would change behavior", i)
		}
		set["type"] = json.RawMessage(`"inline"`)
		set["rules"], err = json.Marshal(rules)
		if err != nil {
			return "", err
		}
		delete(set, "path")
		delete(set, "format")
	}
	if sets != nil {
		route["rule_set"], _ = json.Marshal(sets)
		root["route"], _ = json.Marshal(route)
	}
	data, err = json.Marshal(root)
	if err != nil {
		return "", err
	}
	// Strict option decoding, without the compatibility wrapper/private metadata.
	// No Start/network/file writes are performed by export validation.
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	var opts option.Options
	if err = opts.UnmarshalJSONContext(ctx, data); err != nil {
		return "", fmt.Errorf("portable export: %w", err)
	}
	return string(data), nil
}

// NekoBox installs VPN routes in its own VpnService; SFA consumes TUN options.
// Only official exports need this default. Explicit false (and every DNS mode)
// is preserved. In 1.14.3 auto_route also defaults an unset dns_mode to hijack.
func applyAndroidSFAExportDefaults(root map[string]json.RawMessage) error {
	raw, present := root["inbounds"]
	if !present {
		return nil
	}
	var inbounds []map[string]json.RawMessage
	if err := json.Unmarshal(raw, &inbounds); err != nil {
		return fmt.Errorf("inbounds: %w", err)
	}
	changed := false
	for _, inbound := range inbounds {
		var kind string
		_ = json.Unmarshal(inbound["type"], &kind)
		if kind != "tun" {
			continue
		}
		if _, explicit := inbound["auto_route"]; !explicit {
			inbound["auto_route"] = json.RawMessage(`true`)
			changed = true
		}
	}
	if changed {
		var err error
		root["inbounds"], err = json.Marshal(inbounds)
		return err
	}
	return nil
}
