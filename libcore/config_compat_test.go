package libcore

import (
	"encoding/json"
	"testing"
)

func TestUpgradeLegacyWireGuardOutbound(t *testing.T) {
	input := []byte(`{"outbounds":[{"type":"wireguard","tag":"proxy","server":"vpn.example","server_port":51820,"local_address":["10.0.0.2/32"],"private_key":"private","peer_public_key":"public","pre_shared_key":"psk"}]}`)
	output, err := upgradeLegacyWireGuardOutbounds(input)
	if err != nil {
		t.Fatal(err)
	}
	var config struct {
		Outbounds []map[string]any `json:"outbounds"`
		Endpoints []map[string]any `json:"endpoints"`
	}
	if err = json.Unmarshal(output, &config); err != nil {
		t.Fatal(err)
	}
	if len(config.Outbounds) != 0 || len(config.Endpoints) != 1 {
		t.Fatalf("expected one endpoint and no legacy outbound, got %#v", config)
	}
	endpoint := config.Endpoints[0]
	if endpoint["type"] != "wireguard" || endpoint["tag"] != "proxy" || endpoint["private_key"] != "private" {
		t.Fatalf("unexpected endpoint fields: %#v", endpoint)
	}
	peers, ok := endpoint["peers"].([]any)
	if !ok || len(peers) != 1 {
		t.Fatalf("expected one migrated peer, got %#v", endpoint["peers"])
	}
	peer := peers[0].(map[string]any)
	if peer["address"] != "vpn.example" || peer["port"] != float64(51820) || peer["public_key"] != "public" {
		t.Fatalf("unexpected peer fields: %#v", peer)
	}
	allowedIPs, ok := peer["allowed_ips"].([]any)
	if !ok || len(allowedIPs) != 2 {
		t.Fatalf("legacy default allowed IPs were not retained: %#v", peer["allowed_ips"])
	}
}

func TestUpgradeLegacyWireGuardRejectsUnsupportedSettings(t *testing.T) {
	for _, config := range []string{
		`{"outbounds":[{"type":"wireguard","gso":true}]}`,
		`{"outbounds":[{"type":"wireguard","network":["tcp"]}]}`,
		`{"outbounds":[{"type":"wireguard","network":"tcp"}]}`,
		`{"outbounds":[{"type":"wireguard","gso":"false"}]}`,
	} {
		if _, err := upgradeLegacyWireGuardOutbounds([]byte(config)); err == nil {
			t.Fatalf("expected unsupported legacy setting to fail: %s", config)
		}
	}
}

func TestUpgradeLegacyWireGuardAllowsNoopFilters(t *testing.T) {
	for _, fields := range []string{
		`"gso":false`,
		`"gso":null`,
		`"network":[]`,
		`"network":null`,
		`"network":""`,
		`"network":false`,
	} {
		input := []byte(`{"outbounds":[{"type":"wireguard","tag":"proxy",` + fields + `}]}`)
		output, err := upgradeLegacyWireGuardOutbounds(input)
		if err != nil {
			t.Fatalf("expected no-op filter %s to migrate: %v", fields, err)
		}
		var config struct {
			Outbounds []map[string]any `json:"outbounds"`
			Endpoints []map[string]any `json:"endpoints"`
		}
		if err = json.Unmarshal(output, &config); err != nil {
			t.Fatalf("decode migrated config with %s: %v", fields, err)
		}
		if len(config.Outbounds) != 0 || len(config.Endpoints) != 1 {
			t.Fatalf("expected one migrated endpoint with %s, got %#v", fields, config)
		}
		if _, exists := config.Endpoints[0]["gso"]; exists {
			t.Fatalf("no-op gso setting was retained for %s", fields)
		}
		if _, exists := config.Endpoints[0]["network"]; exists {
			t.Fatalf("no-op network setting was retained for %s", fields)
		}
	}
}

func TestUpgradeLegacyWireGuardMigratesPeerList(t *testing.T) {
	input := []byte(`{"outbounds":[{"type":"wireguard","tag":"proxy","private_key":"local","peers":[{"server":"one.example","server_port":51820,"public_key":"one","allowed_ips":["10.0.0.0/8"]},{"server":"two.example","server_port":51821,"public_key":"two","reserved":[1,2,3]}]}]}`)
	output, err := upgradeLegacyWireGuardOutbounds(input)
	if err != nil {
		t.Fatal(err)
	}
	var config struct {
		Endpoints []map[string]any `json:"endpoints"`
	}
	if err = json.Unmarshal(output, &config); err != nil {
		t.Fatal(err)
	}
	peers, ok := config.Endpoints[0]["peers"].([]any)
	if !ok || len(peers) != 2 {
		t.Fatalf("expected two migrated peers, got %#v", config.Endpoints[0]["peers"])
	}
	first := peers[0].(map[string]any)
	if first["address"] != "one.example" || first["port"] != float64(51820) || first["public_key"] != "one" {
		t.Fatalf("unexpected first migrated peer: %#v", first)
	}
	second := peers[1].(map[string]any)
	if second["address"] != "two.example" || second["port"] != float64(51821) || second["public_key"] != "two" {
		t.Fatalf("unexpected second migrated peer: %#v", second)
	}
}
