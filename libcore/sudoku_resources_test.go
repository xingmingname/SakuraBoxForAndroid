package libcore

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"libcore/sudoku"
	sudokut "libcore/sudoku/transport"
	sudokuo "libcore/sudoku/transport/obfs/sudoku"
)

func f03Out(count int, mode string) map[string]any {
	out := sudokuTestOutbound()
	patterns := make([]string, count)
	for i := range patterns {
		patterns[i] = "xpxvvpvv"
	}
	out["ascii"], out["custom_tables"] = mode, patterns
	return out
}

func TestSudokuResourceCoreCheckAndConstructorRejectBeforeTables(t *testing.T) {
	for _, row := range []struct {
		count int
		mode  string
	}{{4000, "up_ascii_down_entropy"}, {33, "prefer_ascii"}, {17, "up_ascii_down_entropy"}} {
		config := string(sudokuTestConfig(f03Out(row.count, row.mode)))
		runtime.GC()
		var before, after runtime.MemStats
		runtime.ReadMemStats(&before)
		checkAllocations := func(entry string) {
			t.Helper()
			runtime.ReadMemStats(&after)
			delta := after.TotalAlloc - before.TotalAlloc
			if delta > 8<<20 {
				t.Fatalf("%s allocated too much before rejection: %d", entry, delta)
			}
			t.Logf("entries=%d entry=%s allocations=%d", row.count, entry, delta)
			before = after
		}
		if err := CheckConfig(config); !errors.Is(err, sudokuo.ErrTableResourceLimit) {
			t.Fatalf("check %d: %v", row.count, err)
		}
		checkAllocations("CheckConfig")
		core, err := newSingBoxInstance(config, nil, nil)
		if core != nil || !errors.Is(err, sudokuo.ErrTableResourceLimit) {
			t.Fatalf("construct %d: %v", row.count, err)
		}
		checkAllocations("constructor")
		control := newPinnedControl()
		testCore, testErr := NewTestSingBoxInstance(config, new(pinnedResolver), control)
		if testCore != nil || !errors.Is(testErr, sudokuo.ErrTableResourceLimit) || control.calls.Load() != 0 {
			t.Fatalf("URL Test constructed/bound over-budget node: %v", testErr)
		}
		checkAllocations("URLTestConstructor")
	}
	f03ConnectAfterFailure(t)
}

func TestSudokuWholeConfigBudgetsRemainIndependent(t *testing.T) {
	outs := []any{}
	for i := 0; i < 33; i++ {
		out := sudokuTestOutbound()
		out["tag"] = fmt.Sprintf("sudoku-%d", i)
		outs = append(outs, out)
	}
	data, _ := json.Marshal(map[string]any{"outbounds": outs})
	if err := CheckConfig(string(data)); err != nil {
		t.Fatal(err)
	}
	instanceAll, err := newSingBoxInstance(string(data), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = instanceAll.Close(); err != nil {
		t.Fatal(err)
	}
	under, _ := json.Marshal(map[string]any{"log": map[string]any{"disabled": true}, "outbounds": outs[:32]})
	instance, err := newSingBoxInstance(string(under), nil, nil)
	if err != nil {
		t.Fatal("aggregate count boundary", err)
	}
	if err = instance.Close(); err != nil {
		t.Fatal(err)
	}
	for _, row := range []struct {
		count int
		mode  string
	}{{32, "prefer_ascii"}, {16, "up_ascii_down_entropy"}} {
		if err := CheckConfig(string(sudokuTestConfig(f03Out(row.count, row.mode)))); err != nil {
			t.Fatal(err)
		}
	}
	for _, v := range outs {
		out := v.(map[string]any)
		out["type"] = "direct"
		delete(out, "server")
		delete(out, "server_port")
		delete(out, "key")
	}
	data, _ = json.Marshal(map[string]any{"outbounds": outs})
	if err := CheckConfig(string(data)); err != nil {
		t.Fatal("other protocols affected", err)
	}
}

func TestSudokuAggregateMemoryStatisticsDoNotOverrideNodeBudgets(t *testing.T) {
	key := strings.Repeat("non-hex-key", (1<<20)/11)
	var opts option.Options
	for i := 0; i < 8; i++ {
		out := &sudoku.OutboundOptions{Key: key}
		out.Server = "127.0.0.1"
		out.ServerPort = 443
		opts.Outbounds = append(opts.Outbounds, option.Outbound{Type: "sudoku", Tag: fmt.Sprint(i), Options: out})
	}
	usage, err := estimateSudokuResources(opts)
	if err != nil || usage.EstimatedBytes <= sudokuo.MaxTableBuildBytes || usage.PhysicalTables != 8 {
		t.Fatal("independent node budgets or whole-config statistics", usage, err)
	}
}

func TestSudokuResourceJVMEntryFixtures(t *testing.T) {
	directory := os.Getenv("NEKO_CONFIG_FIXTURES")
	if directory == "" {
		t.Skip("JVM fixtures not supplied")
	}
	paths, err := filepath.Glob(filepath.Join(directory, "f03", "*.json"))
	if err != nil || len(paths) == 0 {
		t.Fatalf("F03 JVM fixtures missing: %v", err)
	}
	for _, path := range paths {
		t.Run(filepath.Base(path), func(t *testing.T) {
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			var raw map[string]any
			if err = json.Unmarshal(data, &raw); err != nil {
				t.Fatal(err)
			}
			if _, full := raw["outbounds"]; !full {
				raw["tag"] = "proxy"
				data = sudokuTestConfig(raw)
			}
			limited := strings.HasPrefix(filepath.Base(path), "reject-")
			err = CheckConfig(string(data))
			if errors.Is(err, sudokuo.ErrTableResourceLimit) != limited || err != nil && !limited {
				t.Fatalf("check: %v", err)
			}
			instance, err := newSingBoxInstance(string(data), nil, nil)
			if limited {
				if instance != nil || !errors.Is(err, sudokuo.ErrTableResourceLimit) {
					t.Fatalf("constructor: %v", err)
				}
			} else {
				if err != nil {
					t.Fatal(err)
				}
				if err = instance.Close(); err != nil {
					t.Fatal(err)
				}
			}
		})
	}
	f03ConnectAfterFailure(t)
}

func f03ConnectAfterFailure(t *testing.T, limits ...uint64) {
	t.Helper()
	cfg := sudokut.DefaultConfig()
	cfg.Key = "local-test-key"
	cfg.DisableHTTPMask = true
	tables, err := sudokut.NewTablesWithCustomPatterns(cfg.Key, "prefer_ascii", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	cfg.Tables = tables
	server, err := sudokut.NewHTTPMaskTunnelServer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			raw, e := listener.Accept()
			if e != nil {
				return
			}
			go func() {
				defer raw.Close()
				raw.SetDeadline(time.Now().Add(5 * time.Second))
				conn, kind, target, _, _, handled, e := server.HandleConnSessionAutoWithUserHash(raw)
				if e == nil && handled && conn != nil {
					defer conn.Close()
					if kind == sudokut.SessionForward {
						if target == "f03-urltest.invalid:80" {
							if _, e := http.ReadRequest(bufio.NewReader(conn)); e == nil {
								io.WriteString(conn, "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
							}
						} else {
							io.Copy(conn, conn)
						}
					}
				}
			}()
		}
	}()
	out := sudokuTestOutbound()
	if len(limits) != 0 {
		out["table_limit"] = limits[0]
	}
	out["server_port"] = listener.Addr().(*net.TCPAddr).Port
	out["httpmask"] = map[string]any{"disable": true}
	instance, err := newSingBoxInstance(string(sudokuTestConfig(out)), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	registered, ok := instance.Outbound().Outbound("proxy")
	if !ok {
		t.Fatal("missing outbound")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := registered.DialContext(ctx, "tcp", M.ParseSocksaddr("example.invalid:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = conn.Write([]byte("f03-recovery")); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, len("f03-recovery"))
	if _, err = io.ReadFull(conn, reply); err != nil || string(reply) != "f03-recovery" {
		t.Fatal("recovery echo", string(reply), err)
	}
	var testConfig map[string]any
	if err := json.Unmarshal(sudokuTestConfig(out), &testConfig); err != nil {
		t.Fatal(err)
	}
	testConfig["route"].(map[string]any)["auto_detect_interface"] = true
	raw, _ := json.Marshal(testConfig)
	control := newPinnedControl()
	urlCore, err := NewTestSingBoxInstance(string(raw), new(pinnedResolver), control)
	if err != nil {
		t.Fatal(err)
	}
	defer urlCore.Close()
	if err = urlCore.Start(); err != nil {
		t.Fatal(err)
	}
	if _, err = UrlTest(urlCore, "http://f03-urltest.invalid/", 3000); err != nil {
		t.Fatal("URL Test recovery", err)
	}
	if control.calls.Load() == 0 {
		t.Fatal("URL Test bypassed per-instance binding")
	}
}
