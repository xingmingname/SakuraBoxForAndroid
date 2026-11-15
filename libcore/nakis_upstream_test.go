//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/quic-go"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks"
)

func nakisAuditOracle(t *testing.T, path string, settings map[string]any) (int, string) {
	t.Helper()
	if path == "" {
		t.Skip("set the Nakis oracle executable path")
	}
	port := loopbackPort(t, "tcp")
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	const uuid = "aa000000000000000000000000000099"
	options := map[string]any{"listen": addr, "uuid": uuid, "tls": false, "allow_private": true, "log_level": "error"}
	for key, value := range settings {
		options[key] = value
	}
	data, err := json.Marshal(options)
	if err != nil {
		t.Fatal(err)
	}
	cfg := filepath.Join(t.TempDir(), "server.json")
	if err = os.WriteFile(cfg, data, 0600); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(path, "server", "-c", cfg)
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	until := time.Now().Add(3 * time.Second)
	for {
		c, e := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if e == nil {
			_ = c.Close()
			break
		}
		if time.Now().After(until) {
			t.Fatalf("oracle failed to listen: %v", e)
		}
		time.Sleep(20 * time.Millisecond)
	}
	return port, uuid
}

func nakisAuditOutbound(t *testing.T, port int, uuid string, options map[string]any) (adapter.Outbound, context.Context) {
	t.Helper()
	previous := dialer.DoNotSelectInterface
	dialer.DoNotSelectInterface = true
	t.Cleanup(func() { dialer.DoNotSelectInterface = previous })
	node := map[string]any{"type": "nakis", "tag": "nakis", "server": "127.0.0.1", "server_port": port, "uuid": uuid, "tls": false}
	for k, v := range options {
		node[k] = v
	}
	core, ctx := pinnedProtocolCore(t, map[string]any{"route": map[string]any{"auto_detect_interface": true, "final": "nakis"}, "outbounds": []any{node}}, &regressionPlatform{})
	o, ok := core.Outbound().Outbound("nakis")
	if !ok {
		t.Fatal("Nakis outbound missing")
	}
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	t.Cleanup(cancel)
	return o, ctx
}

func nakisTestUDPEcho(t *testing.T, network, address string) net.PacketConn {
	t.Helper()
	conn, err := net.ListenPacket(network, address)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	go func() {
		buffer := make([]byte, 65535)
		for {
			n, addr, err := conn.ReadFrom(buffer)
			if err != nil {
				return
			}
			if _, err = conn.WriteTo(buffer[:n], addr); err != nil {
				return
			}
		}
	}()
	return conn
}

func TestNakisUoTDatagramsDNSAndQUIC(t *testing.T) {
	port, uuid := nakisAuditOracle(t, os.Getenv("NAKIS_ORACLE"), nil)
	o, ctx := nakisAuditOutbound(t, port, uuid, map[string]any{"udp_jitter": "0s"})
	echo := nakisTestUDPEcho(t, "udp4", "127.0.0.1:0")
	relay, err := o.ListenPacket(ctx, M.SocksaddrFromNet(echo.LocalAddr()))
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	_ = relay.SetDeadline(time.Now().Add(4 * time.Second))
	for _, payload := range [][]byte{[]byte("first"), bytes.Repeat([]byte{0x5a}, 4000), []byte("last")} {
		if _, err = relay.WriteTo(payload, echo.LocalAddr()); err != nil {
			t.Fatal(err)
		}
	}
	for _, expected := range [][]byte{[]byte("first"), bytes.Repeat([]byte{0x5a}, 4000), []byte("last")} {
		b := make([]byte, 4096)
		n, source, err := relay.ReadFrom(b)
		if err != nil || !bytes.Equal(b[:n], expected) || source.String() != echo.LocalAddr().String() {
			t.Fatalf("UDP record boundary or source: n=%d source=%v err=%v", n, source, err)
		}
	}
	t.Run("IPv6 and domain", func(t *testing.T) {
		v6 := nakisTestUDPEcho(t, "udp6", "[::1]:0")
		dualStackPort := v6.LocalAddr().(*net.UDPAddr).Port
		nakisTestUDPEcho(t, "udp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(dualStackPort)))
		for _, dst := range []net.Addr{
			v6.LocalAddr(), M.Socksaddr{Fqdn: "localhost", Port: uint16(dualStackPort)},
		} {
			if _, err := relay.WriteTo([]byte("IPv6 or domain"), dst); err != nil {
				t.Fatal(err)
			}
			b := make([]byte, 64)
			n, source, err := relay.ReadFrom(b)
			if err != nil || string(b[:n]) != "IPv6 or domain" || M.SocksaddrFromNet(source).Port != M.SocksaddrFromNet(dst).Port {
				t.Fatalf("UDP target %v: %s %v %v", dst, b[:n], source, err)
			}
		}
	})
	t.Run("DNS", func(t *testing.T) {
		server, err := net.ListenPacket("udp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer server.Close()
		go func() {
			b := make([]byte, 1500)
			n, from, e := server.ReadFrom(b)
			if e != nil {
				return
			}
			var q dns.Msg
			if q.Unpack(b[:n]) != nil {
				return
			}
			ans := new(dns.Msg).SetReply(&q)
			ans.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 30}, A: net.IPv4(192, 0, 2, 13)}}
			if b, e = ans.Pack(); e == nil {
				_, _ = server.WriteTo(b, from)
			}
		}()
		dst := M.SocksaddrFromNet(server.LocalAddr())
		c, err := o.DialContext(ctx, "udp", dst)
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		_ = c.SetDeadline(time.Now().Add(2 * time.Second))
		query := new(dns.Msg).SetQuestion("nakis-test.invalid.", dns.TypeA)
		packed, err := query.Pack()
		if err != nil {
			t.Fatal(err)
		}
		if _, err = c.Write(packed); err != nil {
			t.Fatal(err)
		}
		b := make([]byte, 1500)
		n, err := c.Read(b)
		var reply dns.Msg
		if err != nil || reply.Unpack(b[:n]) != nil || reply.Id != query.Id || len(reply.Answer) != 1 || reply.Answer[0].(*dns.A).A.String() != "192.0.2.13" {
			t.Fatalf("DNS response through UoT: %v", err)
		}
	})
	t.Run("QUIC", func(t *testing.T) {
		identity := mtlsIdentity(t, false, nil)
		cert, err := tls.X509KeyPair([]byte(identity.cert), []byte(identity.key))
		if err != nil {
			t.Fatal(err)
		}
		target, err := net.ListenPacket("udp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer target.Close()
		listener, err := quic.Listen(target, &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"nakis-test"}}, nil)
		if err != nil {
			t.Fatal(err)
		}
		defer listener.Close()
		done := make(chan error, 1)
		go func() {
			q, e := listener.Accept(ctx)
			if e != nil {
				done <- e
				return
			}
			stream, e := q.AcceptStream(ctx)
			if e == nil {
				var b [4]byte
				if _, e = io.ReadFull(stream, b[:]); e == nil {
					if _, e = stream.Write(b[:]); e == nil {
						e = stream.Close()
					}
				}
			}
			done <- e
		}()
		packet, err := o.ListenPacket(ctx, M.SocksaddrFromNet(target.LocalAddr()))
		if err != nil {
			t.Fatal(err)
		}
		defer packet.Close()
		q, err := quic.Dial(ctx, packet, target.LocalAddr(), &tls.Config{InsecureSkipVerify: true, NextProtos: []string{"nakis-test"}}, nil)
		if err != nil {
			t.Fatal(err)
		}
		defer q.CloseWithError(0, "")
		stream, err := q.OpenStreamSync(ctx)
		if err != nil {
			t.Fatal(err)
		}
		_ = stream.SetDeadline(time.Now().Add(3 * time.Second))
		if _, err = stream.Write([]byte("QUIC")); err != nil {
			t.Fatal(err)
		}
		var response [4]byte
		if _, err = io.ReadFull(stream, response[:]); err != nil || string(response[:]) != "QUIC" {
			t.Fatalf("QUIC echo: %v", err)
		}
		if err = <-done; err != nil {
			t.Fatal(err)
		}
	})
}

func nakisAuditSocks5(t *testing.T) (*socks.Client, context.Context) {
	t.Helper()
	port, uuid := nakisAuditOracle(t, os.Getenv("NAKIS_ORACLE"), nil)
	previous := dialer.DoNotSelectInterface
	dialer.DoNotSelectInterface = true
	t.Cleanup(func() { dialer.DoNotSelectInterface = previous })
	inboundPort := loopbackPort(t, "tcp")
	_, ctx := pinnedProtocolCore(t, map[string]any{
		"route":    map[string]any{"auto_detect_interface": true, "final": "nakis"},
		"inbounds": []any{map[string]any{"type": "socks", "listen": "127.0.0.1", "listen_port": inboundPort}},
		"outbounds": []any{map[string]any{"type": "nakis", "tag": "nakis", "server": "127.0.0.1",
			"server_port": port, "uuid": uuid, "tls": false}},
	}, &regressionPlatform{})
	ctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	t.Cleanup(cancel)
	return socks.NewClient(N.SystemDialer, M.ParseSocksaddrHostPort("127.0.0.1", uint16(inboundPort)), socks.Version5, "", ""), ctx
}

func TestNakisSocks5UDPAssociateRoutesToUoT(t *testing.T) {
	client, ctx := nakisAuditSocks5(t)
	target := nakisTestUDPEcho(t, "udp4", "127.0.0.1:0")
	dst := M.SocksaddrFromNet(target.LocalAddr())
	pc, err := client.ListenPacket(ctx, dst)
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	_ = pc.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err = pc.WriteTo([]byte("SOCKS UDP ASSOCIATE"), dst); err != nil {
		t.Fatal(err)
	}
	var reply [64]byte
	n, from, err := pc.ReadFrom(reply[:])
	if err != nil || string(reply[:n]) != "SOCKS UDP ASSOCIATE" || M.SocksaddrFromNet(from) != dst {
		t.Fatalf("SOCKS UDP round trip: %q from %v: %v", reply[:n], from, err)
	}
}

func TestNakisSocks5TCPHalfClosePreservesResponse(t *testing.T) {
	client, ctx := nakisAuditSocks5(t)
	target, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	server := make(chan error, 1)
	go func() {
		conn, err := target.Accept()
		if err != nil {
			server <- err
			return
		}
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
		data, err := io.ReadAll(conn)
		if err == nil {
			_, err = conn.Write(append([]byte("response:"), data...))
		}
		server <- err
	}()
	c, err := client.DialContext(ctx, "tcp", M.SocksaddrFromNet(target.Addr()))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Write([]byte("finish")); err != nil {
		t.Fatal(err)
	}
	if err := c.(*net.TCPConn).CloseWrite(); err != nil {
		t.Fatal(err)
	}
	reply, err := io.ReadAll(c)
	if err != nil || string(reply) != "response:finish" {
		t.Fatalf("SOCKS-routed reply after FIN: %q: %v", reply, err)
	}
	if err := <-server; err != nil {
		t.Fatal(err)
	}
}

func TestNakisUoTRejectsLegacyAndDisabledServer(t *testing.T) {
	for _, item := range []struct {
		name, env string
		settings  map[string]any
	}{
		{"legacy", "NAKIS_LEGACY_ORACLE", nil},
		{"disabled", "NAKIS_ORACLE", map[string]any{"udp": false}},
	} {
		t.Run(item.name, func(t *testing.T) {
			settings := item.settings
			if item.name == "legacy" {
				dnsSocket, err := net.ListenPacket("udp4", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				defer dnsSocket.Close()
				dnsServer := &dns.Server{PacketConn: dnsSocket, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, q *dns.Msg) {
					_ = w.WriteMsg(new(dns.Msg).SetRcode(q, dns.RcodeNameError))
				})}
				go func() { _ = dnsServer.ActivateAndServe() }()
				defer dnsServer.Shutdown()
				settings = map[string]any{"dns": []string{dnsSocket.LocalAddr().String()}}
			}
			port, uuid := nakisAuditOracle(t, os.Getenv(item.env), settings)
			o, ctx := nakisAuditOutbound(t, port, uuid, nil)
			target := nakisTestUDPEcho(t, "udp4", "127.0.0.1:0")
			pc, err := o.ListenPacket(ctx, M.SocksaddrFromNet(target.LocalAddr()))
			if pc != nil {
				pc.Close()
			}
			if err == nil || !strings.Contains(err.Error(), "server must support and enable Nakis UDP") {
				t.Fatalf("expected explicit UoT error: %v", err)
			}
		})
	}
}
