//go:build with_gvisor && with_quic && with_utls && with_clash_api

package libcore

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
)

func secureAPIOptions(t *testing.T, port int) (map[string]any, string) {
	t.Helper()
	directory := os.Getenv("NEKO_CONFIG_FIXTURES")
	if directory == "" {
		t.Fatal("production JVM auth fixture directory is required")
	}
	data, err := os.ReadFile(filepath.Join(directory, "auth", "clash-api-secure.json"))
	if err != nil {
		t.Fatal(err)
	}
	var options map[string]any
	if err = json.Unmarshal(data, &options); err != nil {
		t.Fatal(err)
	}
	api := options["experimental"].(map[string]any)["clash_api"].(map[string]any)
	secret, ok := api["secret"].(string)
	if !ok || len(secret) < 32 {
		t.Fatal("production generator did not authenticate API")
	}
	api["external_controller"] = "127.0.0.1:" + strconv.Itoa(port)
	delete(api, "external_ui")
	options["outbounds"] = []any{map[string]any{"type": "selector", "tag": "proxy", "outbounds": []string{"first", "second"}}, map[string]any{"type": "direct", "tag": "first"}, map[string]any{"type": "direct", "tag": "second"}}
	options["route"] = map[string]any{"final": "proxy"}
	return options, secret
}

func TestAuthenticatedClashManagement(t *testing.T) {
	port := loopbackPort(t, "tcp")
	options, secret := secureAPIOptions(t, port)
	core, _ := urlTestCore(t, options, new(regressionPlatform))
	client := &http.Client{Timeout: 2 * time.Second}
	defer client.CloseIdleConnections()
	base := "http://127.0.0.1:" + strconv.Itoa(port)
	for _, token := range []string{"", "synthetic-wrong", secret} {
		request, _ := http.NewRequest("GET", base+"/proxies", nil)
		if token != "" {
			request.Header.Set("Authorization", "Bearer "+token)
		}
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		io.Copy(io.Discard, response.Body)
		response.Body.Close()
		want := 401
		if token == secret {
			want = 200
		}
		if response.StatusCode != want {
			t.Fatalf("authentication boundary status=%d want=%d", response.StatusCode, want)
		}
	}
	request, _ := http.NewRequest("PUT", base+"/proxies/proxy", strings.NewReader(`{"name":"second"}`))
	request.Header.Set("Authorization", "Bearer "+secret)
	request.Header.Set("Content-Type", "application/json")
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	group, _ := core.Outbound().Outbound("proxy")
	if response.StatusCode != 204 || group.(adapter.OutboundGroup).Now() != "second" {
		t.Fatal("authorized panel switch failed")
	}
	// YACD uses the existing WebSocket query-token mechanism, not a native bridge.
	for _, token := range []string{"synthetic-wrong", secret} {
		request, _ = http.NewRequest("GET", base+"/traffic?token="+token, nil)
		request.Header.Set("Upgrade", "websocket")
		request.Header.Set("Connection", "Upgrade")
		request.Header.Set("Sec-WebSocket-Version", "13")
		request.Header.Set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
		response, err = client.Do(request)
		if err != nil {
			t.Fatal("WebSocket authentication failed without printing URL/token")
		}
		response.Body.Close()
		want := 401
		if token == secret {
			want = 101
		}
		if response.StatusCode != want {
			t.Fatalf("WebSocket status=%d want=%d", response.StatusCode, want)
		}
	}
}

func TestDisabledClashDoesNotListen(t *testing.T) {
	// Own the actual default controller port while a core without Clash API
	// starts. An accidental default listener must fail its Start, not silently
	// bind a different random test port.
	listener, err := net.Listen("tcp", "127.0.0.1:9090")
	if err != nil {
		t.Fatal("default API port unavailable for isolation check")
	}
	defer listener.Close()
	urlTestCore(t, map[string]any{"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}, "route": map[string]any{"final": "direct"}}, new(regressionPlatform))
}

// Explicitly gated host-only peer for the real bundled YACD browser test.
func TestSecurePanelBrowserPeer(t *testing.T) {
	directory := os.Getenv("SB_PANEL_BROWSER_RUN")
	if directory == "" {
		t.Skip("separate browser peer not requested")
	}
	port := loopbackPort(t, "tcp")
	options, _ := secureAPIOptions(t, port)
	api := options["experimental"].(map[string]any)["clash_api"].(map[string]any)
	api["secret"] = "synthetic-only"
	core, _ := urlTestCore(t, options, new(regressionPlatform))
	if err := os.WriteFile(filepath.Join(directory, "peer-ready"), []byte(strconv.Itoa(port)), 0600); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(filepath.Join(directory, "browser-done")); err == nil {
			outbound, _ := core.Outbound().Outbound("proxy")
			if outbound.(adapter.OutboundGroup).Now() != "second" {
				t.Fatal("browser did not switch actual core selector")
			}
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal(fmt.Errorf("local browser peer timed out"))
}
