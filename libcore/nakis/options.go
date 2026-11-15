package nakis

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
)

const Type = "nakis"
const UpstreamCommit = "33eb35178ad6c6774144baf562f589d612578603"

const (
	FrameCap   = BufMax - 2
	PackCap    = FrameCap - 1
	ShapeCap   = 16382
	ShapeSlots = 8
)

type OutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	// Additional official client endpoints share UUID, SNI, pins, and ECH.
	ServerAlternates []string            `json:"server_alternates,omitempty"`
	UUID             string              `json:"uuid"`
	TLS              *bool               `json:"tls,omitempty"`
	SNI              string              `json:"sni,omitempty"`
	TLSPins          []string            `json:"tls_pins,omitempty"`
	ECHConfig        string              `json:"ech_config,omitempty"`
	DialTimeout      badoption.Duration  `json:"dial_timeout,omitempty"`
	HandshakeTimeout badoption.Duration  `json:"handshake_timeout,omitempty"`
	Keepalive        badoption.Duration  `json:"keepalive,omitempty"`
	Congestion       string              `json:"congestion,omitempty"`
	PoolLo           *int                `json:"pool_lo,omitempty"`
	PoolHi           *int                `json:"pool_hi,omitempty"`
	PoolMaxAge       *badoption.Duration `json:"pool_max_age,omitempty"`
	PoolMaxUses      *int                `json:"pool_max_uses,omitempty"`
	RaceCount        *int                `json:"race_count,omitempty"`
	SendPack         *int                `json:"send_pack,omitempty"`
	SendTail         *int                `json:"send_tail,omitempty"`
	SendQuiet        *int                `json:"send_quiet,omitempty"`
	ShapeUp          [][2]int            `json:"shape_up,omitempty"`
	ShapeWait        badoption.Duration  `json:"shape_wait,omitempty"`
	HalfCloseTimeout badoption.Duration  `json:"half_close_timeout,omitempty"`
	UDPJitter        *badoption.Duration `json:"udp_jitter,omitempty"`
}

type clientConfig struct {
	servers                                                               []M.Socksaddr
	uuid                                                                  []byte
	tls                                                                   bool
	sni                                                                   string
	pins                                                                  [][sha256.Size]byte
	ech                                                                   []byte
	dialTimeout, handshakeTimeout, keepalive, poolMaxAge, shapeWait       time.Duration
	halfCloseTimeout, udpJitter                                           time.Duration
	poolLo, poolHi, poolMaxUses, raceCount, sendPack, sendTail, sendQuiet int
	shape                                                                 []Range
}

func integer(value *int, def, low, high int) int {
	if value == nil {
		return min(max(def, low), high)
	}
	return min(max(*value, low), high)
}

func positiveDuration(value badoption.Duration, fallback time.Duration) time.Duration {
	if value <= 0 {
		return fallback
	}
	return time.Duration(value)
}

// ValidateOptions is also used by the socket-free internal config check.
func ValidateOptions(options OutboundOptions) error {
	_, err := resolveOptions(options)
	return err
}

func normHost(h string) (string, error) {
	h = strings.TrimSpace(h)
	if h == "" {
		return "", errors.New("host is empty")
	}
	if strings.ContainsRune(h, '%') {
		return "", fmt.Errorf("host %q must not carry a zone", h)
	}
	if a, err := netip.ParseAddr(h); err == nil {
		return a.String(), nil
	}
	h = strings.TrimSuffix(h, ".")
	if h == "" || len(h) > 253 || h[0] == '.' || strings.Contains(h, "..") {
		return "", fmt.Errorf("host %q is not a valid name", h)
	}
	for _, r := range h {
		if r <= 0x20 || r == 0x7f || strings.ContainsRune(`/\?#@,[]:"'<>`, r) {
			return "", fmt.Errorf("host %q is not a valid name", h)
		}
	}
	return strings.ToLower(h), nil
}

func normServer(s string) (string, error) {
	host, port, err := net.SplitHostPort(strings.TrimSpace(s))
	if err != nil {
		return "", fmt.Errorf("server %q: %w", s, err)
	}
	n, err := strconv.ParseUint(port, 10, 16)
	if err != nil || n < 1 {
		return "", fmt.Errorf("server %q: port must be 1-65535", s)
	}
	if strings.ContainsRune(host, '%') {
		a, perr := netip.ParseAddr(host)
		if perr != nil || a.Zone() == "" {
			return "", fmt.Errorf("server %q: bad address", s)
		}
		host = a.String()
	} else if host, err = normHost(host); err != nil {
		return "", fmt.Errorf("server %q: %w", s, err)
	}
	return net.JoinHostPort(host, strconv.Itoa(int(n))), nil
}

func resolveOptions(o OutboundOptions) (clientConfig, error) {
	var c clientConfig
	if o.ServerPort == 0 {
		return c, errors.New("nakis: server and server_port are required")
	}
	host := strings.TrimSpace(o.Server)
	if strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]") {
		host = host[1 : len(host)-1]
	}
	first, err := normServer(net.JoinHostPort(host, strconv.Itoa(int(o.ServerPort))))
	if err != nil {
		return c, fmt.Errorf("nakis: %w", err)
	}
	c.servers = []M.Socksaddr{M.ParseSocksaddr(first)}
	seen := map[string]bool{first: true}
	for i, server := range o.ServerAlternates {
		if strings.TrimSpace(server) == "" {
			continue
		}
		endpoint, err := normServer(server)
		if err != nil {
			return c, fmt.Errorf("nakis: invalid server_alternates[%d]: %w", i, err)
		}
		if seen[endpoint] {
			continue
		}
		seen[endpoint] = true
		c.servers = append(c.servers, M.ParseSocksaddr(endpoint))
	}
	uuid := strings.ReplaceAll(o.UUID, "-", "")
	id, err := hex.DecodeString(uuid)
	if err != nil || len(id) != 16 {
		return c, errors.New("nakis: UUID must contain 32 hexadecimal digits")
	}
	c.uuid = id
	c.tls = o.TLS == nil || *o.TLS
	if o.SNI != "" {
		c.sni, err = normHost(o.SNI)
		if err != nil {
			return c, fmt.Errorf("nakis: invalid SNI: %w", err)
		}
	}
	if !c.tls && (len(o.TLSPins) > 0 || o.ECHConfig != "") {
		return c, errors.New("nakis: pins and ECH require TLS")
	}
	if c.tls && c.sni == "" && (len(o.TLSPins) == 0 || o.ECHConfig != "") {
		return c, errors.New("nakis: TLS needs SNI or pins, and ECH always needs SNI")
	}
	for i, value := range o.TLSPins {
		raw, err := hex.DecodeString(strings.ReplaceAll(strings.TrimSpace(value), ":", ""))
		if err != nil || len(raw) != sha256.Size {
			return c, fmt.Errorf("nakis: tls_pins[%d] must be a SHA-256 hex digest", i)
		}
		c.pins = append(c.pins, [sha256.Size]byte(raw))
	}
	if o.ECHConfig != "" {
		list, err := base64.StdEncoding.DecodeString(o.ECHConfig)
		if err != nil {
			return c, errors.New("nakis: invalid ECHConfigList")
		}
		c.ech = list
	}
	c.dialTimeout = positiveDuration(o.DialTimeout, 8*time.Second)
	c.handshakeTimeout = positiveDuration(o.HandshakeTimeout, 10*time.Second)
	c.keepalive = positiveDuration(o.Keepalive, 15*time.Second)
	c.poolLo = integer(o.PoolLo, 2, 1, 1024)
	c.poolHi = integer(o.PoolHi, 32, c.poolLo+1, 4096)
	c.poolMaxAge = 45 * time.Second
	if o.PoolMaxAge != nil {
		c.poolMaxAge = positiveDuration(*o.PoolMaxAge, 15*time.Second)
	}
	c.poolMaxUses = integer(o.PoolMaxUses, 8, 0, 100)
	c.raceCount = integer(o.RaceCount, 3, 2, 12)
	c.sendPack = integer(o.SendPack, 20*1024, 1024, PackCap)
	c.sendTail = integer(o.SendTail, 512, 0, c.sendPack-1)
	c.sendQuiet = integer(o.SendQuiet, 4, 0, 64)
	c.shapeWait = positiveDuration(o.ShapeWait, 20*time.Millisecond)
	c.halfCloseTimeout = positiveDuration(o.HalfCloseTimeout, 120*time.Second)
	c.udpJitter = 8 * time.Millisecond
	if o.UDPJitter != nil {
		c.udpJitter = min(max(time.Duration(*o.UDPJitter), 0), 200*time.Millisecond)
	}
	if len(o.ShapeUp) > ShapeSlots {
		return c, errors.New("nakis: too many shape_up ranges")
	}
	for i, span := range o.ShapeUp {
		if span[0] < 1 || span[1] < span[0] || span[1] > ShapeCap {
			return c, fmt.Errorf("nakis: invalid shape_up[%d]", i)
		}
		c.shape = append(c.shape, Range{Min: span[0], Max: span[1]})
	}
	return c, nil
}
