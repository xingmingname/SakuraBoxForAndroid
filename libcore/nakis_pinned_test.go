//go:build with_gvisor && with_quic && with_utls

package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/urltest"
)

func TestNakisPinnedURLTestSelectorChainAndReset(t *testing.T) {
	oracle := os.Getenv("NAKIS_ORACLE")
	if oracle == "" {
		t.Skip("set NAKIS_ORACLE to the pinned upstream executable")
	}
	previous := dialer.DoNotSelectInterface
	dialer.DoNotSelectInterface = true
	t.Cleanup(func() { dialer.DoNotSelectInterface = previous })
	port := loopbackPort(t, "tcp")
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	const uuid = "6c1c4a52-3b8e-4d9a-9a52-0d4f5f0f7a11"
	path := filepath.Join(t.TempDir(), "nakis.json")
	data, err := json.Marshal(map[string]any{"listen": addr, "uuid": uuid, "tls": false, "allow_private": true, "log_level": "error"})
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	peer := exec.Command(oracle, "server", "-c", path)
	if err = peer.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = peer.Process.Kill(); _ = peer.Wait() })
	end := time.Now().Add(3 * time.Second)
	for {
		c, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err == nil {
			c.Close()
			break
		}
		if time.Now().After(end) {
			t.Fatal(err)
		}
		time.Sleep(20 * time.Millisecond)
	}
	var hits atomic.Int32
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits.Add(1); w.WriteHeader(http.StatusNoContent) }))
	defer target.Close()
	node := map[string]any{"type": "nakis", "uuid": uuid, "tls": false}
	if directory := os.Getenv("NAKIS_URI_JVM_FIXTURES"); directory != "" {
		raw, err := os.ReadFile(filepath.Join(directory, "plain.json"))
		if err != nil {
			t.Fatal(err)
		}
		node = nil
		if err = json.Unmarshal(raw, &node); err != nil {
			t.Fatal(err)
		}
		if node["type"] != "nakis" || node["uuid"] != uuid || node["tls"] != false || node["congestion"] != "reno" {
			t.Fatal("unexpected JVM-imported plain Nakis fixture")
		}
	}
	// Use this test's ephemeral listener, preserving the imported protocol options.
	node["tag"], node["server"], node["server_port"], node["detour"] = "nakis", "127.0.0.1", port, "underlay"
	config := map[string]any{
		"route": map[string]any{"auto_detect_interface": true, "final": "select"},
		"outbounds": []any{
			map[string]any{"type": "selector", "tag": "select", "outbounds": []string{"nakis", "underlay"}},
			node,
			map[string]any{"type": "direct", "tag": "underlay", "connect_timeout": "2s"},
		},
	}
	platform := &regressionPlatform{}
	box, ctx := pinnedProtocolCore(t, config, platform)
	selector, ok := box.Outbound().Outbound("select")
	if !ok {
		t.Fatal("selector missing")
	}
	group, ok := selector.(interface {
		adapter.OutboundGroup
		SelectOutbound(string) bool
	})
	if !ok || group.Now() != "nakis" {
		t.Fatal("selector did not select Nakis")
	}
	for i, tag := range []string{"nakis", "nakis", "underlay", "nakis"} {
		if !group.SelectOutbound(tag) || group.Now() != tag {
			t.Fatalf("could not select %s", tag)
		}
		testCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
		_, err := urltest.URLTest(testCtx, target.URL, selector)
		cancel()
		if err != nil {
			t.Fatal(err)
		}
		if i == 0 {
			box.Network().ResetNetwork(ctx)
		}
	}
	if hits.Load() != 4 || platform.calls.Load() < 2 {
		t.Fatalf("expected four HTTP requests with protected dialing and selector changes, hits=%d control=%d", hits.Load(), platform.calls.Load())
	}
}
