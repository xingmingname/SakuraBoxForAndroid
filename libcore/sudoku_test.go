package libcore

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"libcore/sudoku"
	sudokut "libcore/sudoku/transport"
	sudokuc "libcore/sudoku/transport/crypto"

	M "github.com/sagernet/sing/common/metadata"
)

func sudokuTestConfig(out map[string]any) []byte {
	data, _ := json.Marshal(map[string]any{
		"log":       map[string]any{"disabled": true},
		"outbounds": []any{out},
		"route":     map[string]any{"final": "proxy"},
	})
	return data
}

func sudokuTestOutbound() map[string]any {
	return map[string]any{"type": "sudoku", "tag": "proxy", "server": "127.0.0.1", "server_port": 12345, "key": "local-test-key"}
}

func TestSudokuRejectsInvalidOptionsBeforeDialing(t *testing.T) {
	for _, test := range []struct {
		field string
		value any
		want  string
	}{
		{"server", "", "missing server"},
		{"server_port", 0, "missing server_port"},
		{"key", "", "missing key"},
		{"aead", "invalid", "invalid aead"},
		{"padding_min", -1, "padding_min"},
		{"padding_max", 101, "padding_max"},
		{"ascii", "invalid", "ascii"},
		{"custom_table", "invalid", "custom table"},
		{"multiplex", "invalid", "multiplex"},
		{"httpmask", map[string]any{"mode": "invalid"}, "http_mask_mode"},
		{"httpmask", map[string]any{"path_root": "a/b"}, "http_mask_path_root"},
		{"unknown_option", true, "unknown field"},
		{"httpmask", map[string]any{"unknown_option": true}, "unknown field"},
		{"reverse", map[string]any{"routes": []any{map[string]any{"target": "127.0.0.1:8080"}}}, "unknown field"},
	} {
		t.Run(fmt.Sprint(test.field, "=", test.value), func(t *testing.T) {
			out := sudokuTestOutbound()
			if test.field == "custom_table" {
				out["ascii"] = "prefer_entropy"
			}
			out[test.field] = test.value
			err := startCoreConfig(string(sudokuTestConfig(out)), true)
			if err == nil || !strings.Contains(strings.ToLower(err.Error()), test.want) {
				t.Fatalf("expected %q, got %v", test.want, err)
			}
		})
	}
}

func TestSudokuInternalConfigPreservedAndOfficialExportRejected(t *testing.T) {
	if !strings.Contains(VersionBox(), "sudoku: "+sudoku.UpstreamCommit) {
		t.Fatalf("experimental core version lacks Sudoku provenance: %s", VersionBox())
	}
	out := sudokuTestOutbound()
	out["httpmask"] = map[string]any{"mode": "ws", "tls": true, "host": "example.invalid", "path_root": "local", "multiplex": "auto"}
	original := sudokuTestConfig(out)
	migrated, err := MigrateConfig(string(original))
	if err != nil {
		t.Fatal(err)
	}
	var got, want any
	_ = json.Unmarshal([]byte(migrated), &got)
	_ = json.Unmarshal(original, &want)
	a, _ := json.Marshal(got)
	b, _ := json.Marshal(want)
	if !bytes.Equal(a, b) {
		t.Fatalf("migration changed Sudoku options: %s", migrated)
	}
	if err := startCoreConfig(migrated, true); err != nil {
		t.Fatal(err)
	}
	if _, err = ExportPortableConfig(migrated); err == nil || !strings.Contains(err.Error(), "official sing-box") {
		t.Fatalf("official export must reject Sudoku: %v", err)
	}
	var output bytes.Buffer
	w := &redactingLogWriter{writer: &output}
	_, _ = w.Write([]byte(`{"key":"local-`))
	_, _ = w.Write([]byte(`test-key","type":"sudoku"}`))
	_ = w.Flush()
	if strings.Contains(output.String(), "local-test-key") || !strings.Contains(output.String(), "REDACTED") {
		t.Fatalf("Sudoku key was not redacted across writes: %s", output.String())
	}
}

func TestSudokuRegisteredOutboundLoopbackTCPAndUDP(t *testing.T) {
	for _, mode := range []string{"disabled", "legacy", "auto", "ws", "mux"} {
		for _, aead := range []string{"chacha20-poly1305", "aes-128-gcm"} {
			for _, splitKey := range []bool{false, true} {
				t.Run(fmt.Sprintf("%s/%s/split=%t", mode, aead, splitKey), func(t *testing.T) {
					serverKey, clientKey := "local-test-key", "local-test-key"
					if splitKey {
						pair, err := sudokuc.GenerateMasterKey()
						if err != nil {
							t.Fatal(err)
						}
						serverKey = hex.EncodeToString(pair.Public.Bytes())
						clientKey, err = sudokuc.SplitPrivateKey(pair.Private)
						if err != nil {
							t.Fatal(err)
						}
					}
					cfg := sudokut.DefaultConfig()
					cfg.Key, cfg.AEADMethod = serverKey, aead
					cfg.DisableHTTPMask = mode == "disabled"
					cfg.HTTPMaskMode, cfg.HTTPMaskPathRoot = mode, "local"
					if mode == "mux" {
						cfg.HTTPMaskMode, cfg.Multiplex = "auto", "on"
					}
					if cfg.DisableHTTPMask {
						cfg.HTTPMaskMode = "legacy"
					}
					tables, err := sudokut.NewTablesWithCustomPatterns(serverKey, "prefer_ascii", "", nil)
					if err != nil {
						t.Fatal(err)
					}
					cfg.Tables = tables
					server, err := sudokut.NewHTTPMaskTunnelServer(cfg)
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() { _ = server.Close() })
					listener, err := net.Listen("tcp", "127.0.0.1:0")
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() { _ = listener.Close() })
					go func() {
						for {
							raw, err := listener.Accept()
							if err != nil {
								return
							}
							go func() {
								defer raw.Close()
								_ = raw.SetDeadline(time.Now().Add(10 * time.Second))
								conn, kind, target, _, _, handled, err := server.HandleConnSessionAutoWithUserHash(raw)
								if err != nil || !handled || conn == nil {
									return
								}
								defer conn.Close()
								if kind == sudokut.SessionForward && target == "example.invalid:443" {
									_, _ = io.Copy(conn, conn)
								} else if kind == sudokut.SessionMux {
									_ = sudokut.HandleMuxServer(conn, func(stream net.Conn, target string) {
										defer stream.Close()
										if target == "example.invalid:443" {
											_, _ = io.Copy(stream, stream)
										}
									})
								} else if kind == sudokut.SessionUoT {
									for {
										addr, payload, err := sudokut.ReadDatagram(conn)
										if err != nil {
											return
										}
										if err := sudokut.WriteDatagram(conn, addr, payload); err != nil {
											return
										}
									}
								}
							}()
						}
					}()
					out := sudokuTestOutbound()
					out["server_port"], out["key"], out["aead"] = listener.Addr().(*net.TCPAddr).Port, clientKey, aead
					out["multiplex"] = cfg.Multiplex
					out["httpmask"] = map[string]any{"disable": cfg.DisableHTTPMask, "mode": cfg.HTTPMaskMode, "path_root": "local"}
					core, _ := modernTestCore(t, sudokuTestConfig(out))
					if err := core.Start(); err != nil {
						t.Fatal(err)
					}
					registered, ok := core.Outbound().Outbound("proxy")
					if !ok {
						t.Fatal("Sudoku outbound is not registered")
					}
					if _, ok := registered.(*sudoku.Outbound); !ok {
						t.Fatalf("unexpected outbound: %T", registered)
					}
					ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
					defer cancel()
					conn, err := registered.DialContext(ctx, "tcp", M.ParseSocksaddr("example.invalid:443"))
					if err != nil {
						t.Fatal(err)
					}
					defer conn.Close()
					_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
					payload := []byte("sudoku-tcp-loopback")
					if _, err := conn.Write(payload); err != nil {
						t.Fatal(err)
					}
					response := make([]byte, len(payload))
					if _, err := io.ReadFull(conn, response); err != nil {
						t.Fatal(err)
					}
					if !bytes.Equal(response, payload) {
						t.Fatalf("TCP echo: %q", response)
					}
					packets, err := registered.ListenPacket(ctx, M.ParseSocksaddr("192.0.2.1:53"))
					if err != nil {
						t.Fatal(err)
					}
					defer packets.Close()
					_ = packets.SetDeadline(time.Now().Add(5 * time.Second))
					for _, address := range []string{"192.0.2.1:53", "[2001:db8::1]:123", "example.invalid:53"} {
						target := M.ParseSocksaddr(address)
						if _, err := packets.WriteTo([]byte("udp-echo"), target); err != nil {
							t.Fatal(err)
						}
						buffer := make([]byte, 512)
						n, from, err := packets.ReadFrom(buffer)
						if err != nil {
							t.Fatal(err)
						}
						if string(buffer[:n]) != "udp-echo" || M.SocksaddrFromNet(from) != target {
							t.Fatalf("UDP echo: %q from %v, want %v", buffer[:n], from, target)
						}
					}
					if err := core.Close(); err != nil {
						t.Fatal(err)
					}
					_ = conn.SetReadDeadline(time.Now().Add(time.Second))
					_, err = conn.Read(make([]byte, 1))
					if err == nil {
						t.Fatal("connection survived core shutdown")
					}
					if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
						t.Fatalf("core did not close connection: %v", err)
					}
				})
			}
		}
	}
}
