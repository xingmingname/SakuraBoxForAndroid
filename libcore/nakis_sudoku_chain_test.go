//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/urltest"
	M "github.com/sagernet/sing/common/metadata"
	sudokut "libcore/sudoku/transport"
)

func TestNakisSudokuMixedChainTCPAndUDP(t *testing.T) {
	oracle := os.Getenv("NAKIS_ORACLE")
	if oracle == "" {
		t.Skip("set NAKIS_ORACLE to an upstream Nakis executable")
	}
	old := dialer.DoNotSelectInterface
	dialer.DoNotSelectInterface = true
	defer func() { dialer.DoNotSelectInterface = old }()
	const id = "00000000-0000-4000-8000-000000000001"
	port := loopbackPort(t, "tcp")
	nakisAddr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	cfgFile := filepath.Join(t.TempDir(), "fixture.json")
	raw, _ := json.Marshal(map[string]any{"listen": nakisAddr, "uuid": id, "tls": false, "allow_private": true, "log_level": "error"})
	if err := os.WriteFile(cfgFile, raw, 0600); err != nil {
		t.Fatal(err)
	}
	proc := exec.Command(oracle, "server", "-c", cfgFile)
	if err := proc.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = proc.Process.Kill(); _ = proc.Wait() }()
	for start := time.Now(); ; {
		c, e := net.DialTimeout("tcp", nakisAddr, 100*time.Millisecond)
		if e == nil {
			c.Close()
			break
		}
		if time.Since(start) > 3*time.Second {
			t.Fatal(e)
		}
		time.Sleep(20 * time.Millisecond)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	defer srv.Close()
	cfg := sudokut.DefaultConfig()
	cfg.Key = "independent-fixture-key"
	cfg.DisableHTTPMask = true
	cfg.HTTPMaskMode = "legacy"
	tables, e := sudokut.NewTablesWithCustomPatterns(cfg.Key, "prefer_ascii", "", nil)
	if e != nil {
		t.Fatal(e)
	}
	cfg.Tables = tables
	endpoint, e := sudokut.NewHTTPMaskTunnelServer(cfg)
	if e != nil {
		t.Fatal(e)
	}
	defer endpoint.Close()
	l, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		t.Fatal(e)
	}
	defer l.Close()
	go func() {
		for {
			raw, e := l.Accept()
			if e != nil {
				return
			}
			go func() {
				defer raw.Close()
				_ = raw.SetDeadline(time.Now().Add(6 * time.Second))
				c, kind, addr, _, _, handled, e := endpoint.HandleConnSessionAutoWithUserHash(raw)
				if e != nil || !handled || c == nil {
					return
				}
				defer c.Close()
				if kind == sudokut.SessionUoT {
					for {
						a, p, e := sudokut.ReadDatagram(c)
						if e != nil {
							return
						}
						if sudokut.WriteDatagram(c, a, p) != nil {
							return
						}
					}
				}
				if kind != sudokut.SessionForward || (addr != nakisAddr && addr != srv.Listener.Addr().String()) {
					return
				}
				dst, e := net.DialTimeout("tcp", addr, time.Second)
				if e != nil {
					return
				}
				defer dst.Close()
				done := make(chan struct{})
				go func() { _, _ = io.Copy(dst, c); close(done) }()
				_, _ = io.Copy(c, dst)
				c.Close()
				<-done
			}()
		}
	}()
	for _, outer := range []string{"nakis", "sudoku"} {
		t.Run(outer+" egress", func(t *testing.T) {
			n := map[string]any{"type": "nakis", "tag": "nakis", "uuid": id, "tls": false, "server": "127.0.0.1", "server_port": port}
			s := sudokuTestOutbound()
			s["tag"], s["server_port"], s["key"] = "sudoku", l.Addr().(*net.TCPAddr).Port, cfg.Key
			s["httpmask"] = map[string]any{"disable": true}
			if outer == "nakis" {
				n["detour"] = "sudoku"
			} else {
				s["detour"] = "nakis"
			}
			core, ctx := pinnedProtocolCore(t, map[string]any{"route": map[string]any{"auto_detect_interface": true, "final": outer}, "outbounds": []any{n, s}}, &regressionPlatform{})
			out, ok := core.Outbound().Outbound(outer)
			if !ok {
				t.Fatal("outbound missing")
			}
			run, stop := context.WithTimeout(ctx, 5*time.Second)
			defer stop()
			if _, e := urltest.URLTest(run, srv.URL, out); e != nil {
				t.Fatal(e)
			}
			dst := M.ParseSocksaddr("192.0.2.1:53")
			if outer == "nakis" {
				dst = M.SocksaddrFromNet(nakisTestUDPEcho(t, "udp4", "127.0.0.1:0").LocalAddr())
			}
			pc, e := out.ListenPacket(run, dst)
			if e != nil {
				t.Fatal(e)
			}
			defer pc.Close()
			_ = pc.SetDeadline(time.Now().Add(4 * time.Second))
			if _, e = pc.WriteTo([]byte("mixed-uot"), dst); e != nil {
				t.Fatal(e)
			}
			reply := make([]byte, 32)
			size, _, e := pc.ReadFrom(reply)
			if e != nil || string(reply[:size]) != "mixed-uot" {
				t.Fatalf("UDP over Sudoku TCP detour failed: %v", e)
			}
		})
	}
}
