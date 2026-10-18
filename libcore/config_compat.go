package libcore

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// upgradeLegacyWireGuardOutbounds migrates sing-box 1.12's WireGuard outbound
// shape to the WireGuard endpoint shape required by sing-box 1.14.
func upgradeLegacyWireGuardOutbounds(config []byte) ([]byte, error) {
	var root map[string]json.RawMessage
	if err := json.Unmarshal(config, &root); err != nil {
		return nil, err
	}
	var outbounds []json.RawMessage
	if raw := root["outbounds"]; len(raw) != 0 {
		if err := json.Unmarshal(raw, &outbounds); err != nil {
			return nil, fmt.Errorf("decode outbounds: %w", err)
		}
	}
	var endpoints []json.RawMessage
	if raw := root["endpoints"]; len(raw) != 0 {
		if err := json.Unmarshal(raw, &endpoints); err != nil {
			return nil, fmt.Errorf("decode endpoints: %w", err)
		}
	}
	updatedOutbounds := make([]json.RawMessage, 0, len(outbounds))
	var firstEndpointTag string
	for index, rawOutbound := range outbounds {
		var outbound map[string]json.RawMessage
		if err := json.Unmarshal(rawOutbound, &outbound); err != nil {
			return nil, fmt.Errorf("decode outbound: %w", err)
		}
		var outboundType string
		_ = json.Unmarshal(outbound["type"], &outboundType)
		if outboundType != "wireguard" {
			updatedOutbounds = append(updatedOutbounds, rawOutbound)
			continue
		}
		endpoint, err := upgradeLegacyWireGuardOutbound(outbound)
		if err != nil {
			return nil, err
		}
		var tag string
		if raw := endpoint["tag"]; len(raw) > 0 {
			if err := json.Unmarshal(raw, &tag); err != nil {
				return nil, fmt.Errorf("outbounds[%d].tag: expected string", index)
			}
		}
		if tag == "" {
			tag = fmt.Sprint(index)
			endpoint["tag"], _ = json.Marshal(tag)
		}
		if index == 0 {
			firstEndpointTag = tag
		}
		encoded, err := json.Marshal(endpoint)
		if err != nil {
			return nil, err
		}
		endpoints = append(endpoints, encoded)
	}
	if len(updatedOutbounds) == len(outbounds) {
		return config, nil
	}
	// Removing an outbound must not renumber the remaining implicit tags.
	keptIndex := 0
	for index, raw := range outbounds {
		var object map[string]json.RawMessage
		_ = json.Unmarshal(raw, &object)
		var kind, tag string
		_ = json.Unmarshal(object["type"], &kind)
		if raw := object["tag"]; len(raw) > 0 {
			if err := json.Unmarshal(raw, &tag); err != nil {
				return nil, fmt.Errorf("outbounds[%d].tag: expected string", index)
			}
		}
		if kind == "wireguard" {
			continue
		}
		if tag == "" {
			object["tag"], _ = json.Marshal(fmt.Sprint(index))
			updatedOutbounds[keptIndex], _ = json.Marshal(object)
		}
		keptIndex++
	}
	if firstEndpointTag != "" {
		var route map[string]json.RawMessage
		if raw := root["route"]; len(raw) > 0 {
			if err := json.Unmarshal(raw, &route); err != nil {
				return nil, fmt.Errorf("route: %w", err)
			}
		}
		if route == nil {
			route = map[string]json.RawMessage{}
		}
		var final string
		if raw := route["final"]; len(raw) > 0 {
			if err := json.Unmarshal(raw, &final); err != nil {
				return nil, fmt.Errorf("route.final: expected string")
			}
		}
		if final == "" {
			route["final"], _ = json.Marshal(firstEndpointTag)
			root["route"], _ = json.Marshal(route)
		}
	}
	root["outbounds"], _ = json.Marshal(updatedOutbounds)
	root["endpoints"], _ = json.Marshal(endpoints)
	return json.Marshal(root)
}

func upgradeLegacyWireGuardOutbound(outbound map[string]json.RawMessage) (map[string]json.RawMessage, error) {
	endpoint := make(map[string]json.RawMessage, len(outbound))
	for key, value := range outbound {
		endpoint[key] = value
	}
	if raw := outbound["gso"]; len(raw) != 0 && !bytes.Equal(bytes.TrimSpace(raw), []byte("false")) && !bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return nil, fmt.Errorf("WireGuard outbound GSO is not supported by sing-box 1.14 endpoints")
	}
	if raw := outbound["network"]; len(raw) != 0 && !bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		var networkList []string
		if json.Unmarshal(raw, &networkList) == nil && len(networkList) == 0 {
			// The empty legacy filter has no behavioral effect.
		} else if bytes.Equal(bytes.TrimSpace(raw), []byte(`""`)) || bytes.Equal(bytes.TrimSpace(raw), []byte("false")) {
			// Also tolerate empty scalar encodings from older configuration writers.
		} else {
			return nil, fmt.Errorf("WireGuard outbound network filter is not supported by sing-box 1.14 endpoints")
		}
	}
	endpoint["type"] = json.RawMessage(`"wireguard"`)
	for oldKey, newKey := range map[string]string{
		"system_interface": "system",
		"interface_name":   "name",
		"local_address":    "address",
	} {
		if value, loaded := endpoint[oldKey]; loaded {
			endpoint[newKey] = value
			delete(endpoint, oldKey)
		}
	}
	for _, removedKey := range []string{"gso", "network", "server", "server_port", "peer_public_key", "pre_shared_key", "reserved"} {
		delete(endpoint, removedKey)
	}
	var peers []map[string]json.RawMessage
	if raw := outbound["peers"]; len(raw) != 0 {
		if err := json.Unmarshal(raw, &peers); err != nil {
			return nil, fmt.Errorf("decode legacy WireGuard peers: %w", err)
		}
		for index, peer := range peers {
			for oldKey, newKey := range map[string]string{"server": "address", "server_port": "port"} {
				if value, loaded := peer[oldKey]; loaded {
					peer[newKey] = value
					delete(peer, oldKey)
				}
			}
			encoded, err := json.Marshal(peer)
			if err != nil {
				return nil, fmt.Errorf("encode legacy WireGuard peer %d: %w", index, err)
			}
			peers[index] = map[string]json.RawMessage{"__encoded": encoded}
		}
	}
	if len(peers) == 0 {
		peer := make(map[string]json.RawMessage)
		for oldKey, newKey := range map[string]string{
			"server": "address", "server_port": "port", "peer_public_key": "public_key",
			"pre_shared_key": "pre_shared_key", "reserved": "reserved",
		} {
			if value, loaded := outbound[oldKey]; loaded {
				peer[newKey] = value
			}
		}
		peer["allowed_ips"] = json.RawMessage(`["0.0.0.0/0","::/0"]`)
		peers = append(peers, peer)
	}
	encodedPeers := make([]json.RawMessage, 0, len(peers))
	for _, peer := range peers {
		if encoded, ok := peer["__encoded"]; ok {
			encodedPeers = append(encodedPeers, encoded)
			continue
		}
		encoded, err := json.Marshal(peer)
		if err != nil {
			return nil, err
		}
		encodedPeers = append(encodedPeers, encoded)
	}
	endpoint["peers"], _ = json.Marshal(encodedPeers)
	return endpoint, nil
}
