package nakis

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"slices"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

const proofSize = 8 + 16 + sha256.Size

func makeProof(id, binding []byte) []byte {
	return makeProofAt(id, binding, time.Now(), randBytes())
}

func randBytes() [16]byte {
	var nonce [16]byte
	rand.Read(nonce[:])
	return nonce
}

func makeProofAt(id, binding []byte, stamp time.Time, nonce [16]byte) []byte {
	proof := make([]byte, proofSize)
	binary.BigEndian.PutUint64(proof, uint64(stamp.UnixNano()))
	copy(proof[8:], nonce[:])
	mac := hmac.New(sha256.New, id)
	mac.Write([]byte("nakis/auth/v1"))
	mac.Write(proof[:24])
	mac.Write(binding)
	return mac.Sum(proof[:24])
}

func clientTLS(ctx context.Context, c clientConfig) *tls.Config {
	if !c.tls {
		return nil
	}
	cfg := &tls.Config{
		ServerName:                     c.sni,
		RootCAs:                        adapter.RootPoolFromContext(ctx),
		MinVersion:                     tls.VersionTLS12,
		NextProtos:                     []string{"h2", "http/1.1"},
		DynamicRecordSizingDisabled:    true,
		ClientSessionCache:             tls.NewLRUClientSessionCache(64),
		EncryptedClientHelloConfigList: c.ech,
	}
	if len(c.ech) > 0 {
		cfg.MinVersion = tls.VersionTLS13
	}
	if len(c.pins) > 0 {
		cfg.InsecureSkipVerify = true
		// Unlike VerifyPeerCertificate, this also runs on resumed TLS sessions.
		cfg.VerifyConnection = func(state tls.ConnectionState) error {
			if len(state.PeerCertificates) == 0 {
				return errors.New("nakis: missing peer certificate")
			}
			pin := sha256.Sum256(state.PeerCertificates[0].RawSubjectPublicKeyInfo)
			if !slices.Contains(c.pins, pin) {
				return errors.New("nakis: peer public key did not match a configured pin")
			}
			return nil
		}
	}
	return cfg
}

func secure(ctx context.Context, raw net.Conn, cfg *tls.Config) (*Tunnel, error) {
	if cfg == nil {
		return WrapTunnel(raw, nil), nil
	}
	batch := NewBatcher(raw)
	conn := tls.Client(batch, cfg)
	if err := conn.HandshakeContext(ctx); err != nil {
		return nil, fmt.Errorf("nakis TLS: %w", err)
	}
	state := conn.ConnectionState()
	binding, err := state.ExportKeyingMaterial("nakis/bind", nil, 32)
	if err != nil {
		return nil, err
	}
	tunnel := WrapTunnel(conn, batch)
	tunnel.bind = binding
	return tunnel, nil
}
