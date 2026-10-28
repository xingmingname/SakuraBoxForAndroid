//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/anytls"
	"github.com/sagernet/sing-box/protocol/trojan"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing-box/protocol/vmess"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

type mtlsTestIdentity struct {
	cert, key, pin string
	parsed         *x509.Certificate
	private        *ecdsa.PrivateKey
}

func mtlsIdentity(t *testing.T, ca bool, parent *mtlsTestIdentity) mtlsTestIdentity {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 100))
	if err != nil {
		t.Fatal(err)
	}
	cert := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "synthetic-mtls.invalid"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: ca, BasicConstraintsValid: true,
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth, x509.ExtKeyUsageServerAuth},
		DNSNames: []string{"localhost", "example.invalid"}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}}
	if ca {
		cert.KeyUsage |= x509.KeyUsageCertSign
	}
	issuer, signer := cert, key
	if parent != nil {
		issuer, signer = parent.parsed, parent.private
	}
	der, err := x509.CreateCertificate(rand.Reader, cert, issuer, &key.PublicKey, signer)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	pin := sha256.Sum256(parsed.RawSubjectPublicKeyInfo)
	return mtlsTestIdentity{string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})),
		string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: encoded})), base64.StdEncoding.EncodeToString(pin[:]), parsed, key}
}

func mtlsFixture(t *testing.T, protocol string) map[string]any {
	t.Helper()
	dir := os.Getenv("NEKO_CONFIG_FIXTURES")
	if dir == "" {
		t.Fatal("Fresh JVM mTLS fixtures are required")
	}
	data, err := os.ReadFile(filepath.Join(dir, "mtls", protocol+".json"))
	if err != nil {
		t.Fatal(err)
	}
	var out map[string]any
	if err = json.Unmarshal(data, &out); err != nil {
		t.Fatal(err)
	}
	tls := out["tls"].(map[string]any)
	if tls["client_certificate"] != "-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n" ||
		tls["client_key"] != "-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----\n" {
		t.Fatal("JVM did not retain the exact multiline identity fields")
	}
	out["tag"] = "mtls-proxy"
	return out
}

func mtlsCore(t *testing.T, config map[string]any, peer bool) (*box.Box, error) {
	t.Helper()
	registry := nekoboxAndroidInboundRegistry()
	if peer {
		anytls.RegisterInbound(registry)
		vless.RegisterInbound(registry)
		vmess.RegisterInbound(registry)
		trojan.RegisterInbound(registry)
	}
	ctx := box.Context(context.Background(), registry, nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	data, err := json.Marshal(config)
	if err != nil {
		return nil, err
	}
	var opts option.Options
	if err = opts.UnmarshalJSONContext(ctx, data); err != nil {
		return nil, err
	}
	core, err := box.New(box.Options{Options: opts, Context: ctx})
	if err == nil {
		t.Cleanup(func() { core.Close() })
	}
	return core, err
}

func TestMTLSGeneratedIdentityConstruction(t *testing.T) {
	client := mtlsIdentity(t, false, nil)
	other := mtlsIdentity(t, false, nil)
	certPath, keyPath := filepath.Join(t.TempDir(), "cert.pem"), filepath.Join(t.TempDir(), "key.pem")
	if err := os.WriteFile(certPath, []byte(client.cert), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, []byte(client.key), 0600); err != nil {
		t.Fatal(err)
	}
	for _, protocol := range []string{"anytls", "vless", "vmess", "trojan", "http"} {
		for _, scenario := range []string{"inline", "paths", "none", "pin-and-identity", "missing-key", "mismatch", "bad-pem", "ca-pin-conflict"} {
			t.Run(protocol+"/"+scenario, func(t *testing.T) {
				out := mtlsFixture(t, protocol)
				tls := out["tls"].(map[string]any)
				tls["client_certificate"], tls["client_key"] = client.cert, client.key
				expected := ""
				switch scenario {
				case "paths":
					delete(tls, "client_certificate")
					delete(tls, "client_key")
					tls["client_certificate_path"], tls["client_key_path"] = certPath, keyPath
				case "none":
					delete(tls, "client_certificate")
					delete(tls, "client_key")
				case "pin-and-identity":
					tls["certificate_public_key_sha256"] = []string{client.pin}
				case "missing-key":
					delete(tls, "client_key")
					expected = "must be provided together"
				case "mismatch":
					tls["client_key"] = other.key
					expected = "private key does not match public key"
				case "bad-pem":
					tls["client_key"] = "not-a-pem"
					expected = "parse client x509 key pair"
				case "ca-pin-conflict":
					tls["certificate"] = client.cert
					tls["certificate_public_key_sha256"] = []string{client.pin}
					expected = "is conflict with certificate"
				}
				config := map[string]any{"log": map[string]any{"disabled": true}, "outbounds": []any{out}}
				_, err := mtlsCore(t, config, false)
				if expected != "" {
					if err == nil || !strings.Contains(err.Error(), expected) {
						t.Fatalf("Expected native rejection category %s", scenario)
					}
					return
				}
				if err != nil {
					t.Fatalf("Native construction failed: %v", err)
				}
				raw, _ := json.Marshal(config)
				exported, err := ExportPortableConfig(string(raw))
				if err != nil {
					t.Fatal(err)
				}
				var decoded map[string]any
				if err = json.Unmarshal([]byte(exported), &decoded); err != nil {
					t.Fatal(err)
				}
				actual := decoded["outbounds"].([]any)[0].(map[string]any)["tls"].(map[string]any)
				for _, key := range []string{"client_certificate", "client_certificate_path", "client_key", "client_key_path"} {
					a, _ := json.Marshal(actual[key])
					b, _ := json.Marshal(tls[key])
					if string(a) != string(b) {
						t.Fatal("Export changed client identity field")
					}
				}
			})
		}
	}
}

func TestMTLSAnyTLSVlessVmessTrojanLocalTraffic(t *testing.T) {
	previous := dialer.DoNotSelectInterface
	dialer.DoNotSelectInterface = true
	t.Cleanup(func() { dialer.DoNotSelectInterface = previous })
	clientCA := mtlsIdentity(t, true, nil)
	client := mtlsIdentity(t, false, &clientCA)
	server := mtlsIdentity(t, false, nil)
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "mtls-local-ok") }))
	defer target.Close()
	for _, protocol := range []string{"anytls", "vless", "vmess", "trojan"} {
		for _, engine := range []string{"std", "utls"} {
			for _, auth := range []string{"ca", "pin"} {
				t.Run(protocol+"/"+engine+"/"+auth, func(t *testing.T) {
					out := mtlsFixture(t, protocol)
					port := loopbackPort(t, "tcp")
					out["server"], out["server_port"] = "127.0.0.1", port
					if protocol == "anytls" || protocol == "trojan" {
						out["password"] = "synthetic-mtls-password"
					}
					user := map[string]any{"name": "synthetic"}
					if protocol == "anytls" || protocol == "trojan" {
						user["password"] = out["password"]
					} else {
						user["uuid"] = out["uuid"]
					}
					peer, err := mtlsCore(t, map[string]any{
						"log": map[string]any{"disabled": true},
						"inbounds": []any{map[string]any{"type": protocol, "tag": "peer", "listen": "127.0.0.1", "listen_port": port,
							"users": []any{user}, "tls": map[string]any{"enabled": true, "certificate": []string{server.cert}, "key": []string{server.key},
								"client_authentication": "require-and-verify", "client_certificate": []string{clientCA.cert}}}},
						"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}, "route": map[string]any{"final": "direct"},
					}, true)
					if err != nil {
						t.Fatal(err)
					}
					if err = peer.Start(); err != nil {
						t.Fatal(err)
					}
					tls := out["tls"].(map[string]any)
					tls["server_name"] = "127.0.0.1"
					tls["insecure"] = false
					tls["client_certificate"], tls["client_key"] = client.cert, client.key
					if auth == "pin" {
						tls["certificate_public_key_sha256"] = []string{server.pin}
					} else {
						tls["certificate"] = []string{server.cert}
					}
					if engine == "utls" {
						tls["utls"] = map[string]any{"enabled": true, "fingerprint": "chrome"}
					} else {
						delete(tls, "utls")
					}
					core, err := mtlsCore(t, map[string]any{"log": map[string]any{"disabled": true}, "outbounds": []any{out}}, false)
					if err != nil {
						t.Fatal(err)
					}
					if err = core.Start(); err != nil {
						t.Fatal(err)
					}
					node, ok := core.Outbound().Outbound("mtls-proxy")
					if !ok {
						t.Fatal("Missing mTLS outbound")
					}
					ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
					defer cancel()
					conn, err := node.DialContext(ctx, "tcp", M.ParseSocksaddr(target.Listener.Addr().String()))
					if err != nil {
						t.Fatal(err)
					}
					defer conn.Close()
					conn.SetDeadline(time.Now().Add(5 * time.Second))
					if _, err = io.WriteString(conn, "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n"); err != nil {
						t.Fatal(err)
					}
					response, err := http.ReadResponse(bufio.NewReader(conn), &http.Request{Method: "GET"})
					if err != nil {
						t.Fatal(err)
					}
					defer response.Body.Close()
					body, err := io.ReadAll(response.Body)
					if err != nil || response.StatusCode != 200 || string(body) != "mtls-local-ok" {
						t.Fatal("No expected traffic through the authenticated local peer")
					}
				})
			}
		}
	}
}
