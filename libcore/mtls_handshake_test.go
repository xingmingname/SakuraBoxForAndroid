//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"bufio"
	"context"
	stdtls "crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	btls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

type mtlsHandshakeLog struct {
	mu       sync.Mutex
	messages []string
}

func (l *mtlsHandshakeLog) Write(data []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.messages = append(l.messages, string(data))
	return len(data), nil
}

func (l *mtlsHandshakeLog) rejected(address, reason string) bool {
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		l.mu.Lock()
		for _, message := range l.messages {
			if strings.Contains(message, address) && strings.Contains(message, reason) {
				l.mu.Unlock()
				return true
			}
		}
		l.mu.Unlock()
		time.Sleep(time.Millisecond)
	}
	return false
}

func TestMTLSLocalHandshakeRejectsMissingUntrustedAndWrongPin(t *testing.T) {
	ca := mtlsIdentity(t, true, nil)
	client := mtlsIdentity(t, false, &ca)
	untrusted := mtlsIdentity(t, false, nil)
	server := mtlsIdentity(t, false, nil)
	roots := x509.NewCertPool()
	roots.AppendCertsFromPEM([]byte(ca.cert))
	target := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 || !r.TLS.PeerCertificates[0].Equal(client.parsed) {
			t.Error("Server did not authenticate the expected client identity")
			http.Error(w, "no identity", 403)
			return
		}
		io.WriteString(w, "verified-client")
	}))
	serverErrors := &mtlsHandshakeLog{}
	target.Config.ErrorLog = log.New(serverErrors, "", 0)
	target.TLS = &stdtls.Config{Certificates: []stdtls.Certificate{{Certificate: [][]byte{server.parsed.Raw}, PrivateKey: server.private}},
		ClientAuth: stdtls.RequireAndVerifyClientCert, ClientCAs: roots, NextProtos: []string{"http/1.1"}}
	target.StartTLS()
	defer target.Close()
	for _, engine := range []string{"std", "utls"} {
		for _, insecure := range []bool{false, true} {
			for _, scenario := range []string{"valid", "missing-client", "untrusted-client", "wrong-pin"} {
				name := engine + "/" + scenario + "/verified"
				if insecure {
					name = engine + "/" + scenario + "/insecure"
				}
				t.Run(name, func(t *testing.T) {
					pin, _ := base64.StdEncoding.DecodeString(server.pin)
					opts := option.OutboundTLSOptions{Enabled: true, ServerName: "127.0.0.1", ALPN: []string{"http/1.1"}, Insecure: insecure,
						CertificatePublicKeySHA256: [][]byte{pin}, ClientCertificate: []string{client.cert}, ClientKey: []string{client.key}}
					if engine == "utls" {
						opts.UTLS = &option.OutboundUTLSOptions{Enabled: true, Fingerprint: "chrome"}
					}
					switch scenario {
					case "missing-client":
						opts.ClientCertificate = nil
						opts.ClientKey = nil
					case "untrusted-client":
						opts.ClientCertificate = []string{untrusted.cert}
						opts.ClientKey = []string{untrusted.key}
					case "wrong-pin":
						opts.CertificatePublicKeySHA256 = [][]byte{make([]byte, 32)}
					}
					config, err := btls.NewClient(context.Background(), logger.NOP(), "127.0.0.1", opts)
					if err != nil {
						t.Fatal(err)
					}
					raw, err := net.DialTimeout("tcp", target.Listener.Addr().String(), 3*time.Second)
					if err != nil {
						t.Fatal(err)
					}
					defer raw.Close()
					clientAddress := raw.LocalAddr().String()
					raw.SetDeadline(time.Now().Add(4 * time.Second))
					conn, err := btls.ClientHandshake(context.Background(), raw, config)
					var body []byte
					if err == nil {
						defer conn.Close()
						_, err = io.WriteString(conn, "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n")
						if err == nil {
							var response *http.Response
							response, err = http.ReadResponse(bufio.NewReader(conn), &http.Request{Method: "GET"})
							if err == nil {
								body, err = io.ReadAll(response.Body)
								response.Body.Close()
							}
						}
					}
					if scenario == "valid" {
						if err != nil || string(body) != "verified-client" {
							t.Fatalf("Valid mTLS handshake/traffic rejected: %v", err)
						}
					} else {
						if err == nil {
							t.Fatal("Invalid client identity or server pin was accepted")
						}
						message := strings.ToLower(err.Error())
						if scenario == "wrong-pin" {
							if !strings.Contains(message, "unrecognized remote public key") {
								t.Fatal("Wrong pin did not fail at public-key authentication")
							}
						} else {
							reason := "client didn't provide a certificate"
							if scenario == "untrusted-client" {
								reason = "failed to verify certificate"
							}
							if !serverErrors.rejected(clientAddress, reason) {
								t.Fatal("Server did not reject this connection at the expected client-certificate check")
							}
						}
					}
				})
			}
		}
	}
}
