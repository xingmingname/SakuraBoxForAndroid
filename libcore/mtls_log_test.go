package libcore

import (
	"bytes"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"

	"github.com/matsuridayo/libneko/neko_log"
)

func TestMTLSLogValuesAndDiagnostics(t *testing.T) {
	tests := []string{
		`{"client_key":["secret-one","secret-two"],"reason":"TLS handshake failed","port":443}`,
		"{\n \"CLIENT_KEY\": [\n \"secret-one\",\n \"secret-two\"\n ],\n \"reason\":\"TLS handshake failed\",\"port\":443\n}",
		`{"client_key_path":"secret-one","private_key":{"nested":["secret-two"]},"reason":"TLS handshake failed","port":443}`,
		"client_key=secret-one; TLS handshake failed; port=443",
		"client-key-path: secret-two\nTLS handshake failed; port=443",
		"-----BEGIN EC PRIVATE KEY-----\nsecret-one\nsecret-two\n-----END EC PRIVATE KEY-----\nTLS handshake failed; port=443",
		"-----BEGIN PRIVATE KEY-----\nsecret-one\n",
	}
	for i, message := range tests {
		t.Run(string(rune('a'+i)), func(t *testing.T) {
			safe := redactLog(message)
			if strings.Contains(safe, "secret-one") || strings.Contains(safe, "secret-two") {
				t.Fatal("sensitive value leaked")
			}
			if strings.Contains(message, "TLS handshake failed") && (!strings.Contains(safe, "TLS handshake failed") || !strings.Contains(safe, "443")) {
				t.Fatal("technical diagnostic removed")
			}
			if redactLog(safe) != safe {
				t.Fatal("redaction must be idempotent")
			}
		})
	}
	technical := "[Debug] AnyTLS: x509: certificate signed by unknown authority\nCaused by: handshake failure\n at service.connect(file.kt:42)\nSNI=example.invalid; ALPN=h2; version=TLS1.3; delay=25ms\n"
	if redactLog(technical) != technical {
		t.Fatal("safe stack/TLS diagnostic changed")
	}
}

func TestMTLSLogEveryWriteSplit(t *testing.T) {
	messages := []string{
		"prefix -----BEGIN RSA PRIVATE KEY-----\nsecret-one\nsecret-two\n-----END RSA PRIVATE KEY----- suffix TLS handshake failed\n",
		"{\n\"client_key\":[\"secret-one\",\n\"secret-two\"],\"reason\":\"TLS handshake failed\"}\n",
		"client_key_path=secret-one; TLS handshake failed\n",
		"https://secret-one:secret-two@example.invalid/ TLS handshake failed\n",
	}
	for _, message := range messages {
		for split := 0; split <= len(message); split++ {
			var out bytes.Buffer
			writer := &redactingLogWriter{writer: &out}
			for _, chunk := range []string{message[:split], message[split:]} {
				n, err := writer.Write([]byte(chunk))
				if err != nil || n != len(chunk) {
					t.Fatal("writer contract failed")
				}
			}
			if err := writer.Flush(); err != nil {
				t.Fatal(err)
			}
			safe := out.String()
			if strings.Contains(safe, "secret-one") || strings.Contains(safe, "secret-two") {
				t.Fatalf("split %d leaked", split)
			}
			if !strings.Contains(safe, "TLS handshake failed") {
				t.Fatalf("split %d removed diagnostic", split)
			}
		}
	}
}

func TestMTLSLogLargeAndConcurrentWrites(t *testing.T) {
	var out bytes.Buffer
	writer := &redactingLogWriter{writer: &out}
	message := `{"client_key":["` + strings.Repeat("private-payload", 20000) + `"],"reason":"large-complete"}` + "\n"
	if _, err := writer.Write([]byte(message)); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out.String(), "private-payload") || !strings.Contains(out.String(), "large-complete") {
		t.Fatal("large value failed")
	}
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); writer.Write([]byte("password=private-payload; TLS diagnostic\n")) }()
	}
	wg.Wait()
	if strings.Contains(out.String(), "private-payload") || strings.Count(out.String(), "TLS diagnostic") != 12 {
		t.Fatal("concurrent diagnostics failed")
	}
}

func TestMTLSLogBoundedChunkBoundary(t *testing.T) {
	const boundary = 64*1024 - 128
	messages := []string{
		`{"client_key":["boundary-secret"],"reason":"boundary diagnostic"}`,
		"-----BEGIN RSA PRIVATE KEY-----boundary-secret-----END RSA PRIVATE KEY----- boundary diagnostic",
		"https://boundary-secret@example.invalid/ boundary diagnostic",
	}
	for _, message := range messages {
		for offset := -48; offset < 48; offset++ {
			var out bytes.Buffer
			writer := &redactingLogWriter{writer: &out}
			input := strings.Repeat(".", boundary+offset) + message + strings.Repeat(".", 256) + "\n"
			if _, err := writer.Write([]byte(input)); err != nil {
				t.Fatal(err)
			}
			if err := writer.Flush(); err != nil {
				t.Fatal(err)
			}
			if strings.Contains(out.String(), "boundary-secret") || !strings.Contains(out.String(), "boundary diagnostic") {
				t.Fatalf("unsafe or lost diagnostic at chunk boundary %d", offset)
			}
		}
	}
}

type mtlsFailWriter struct{}

func (mtlsFailWriter) Write([]byte) (int, error) { return 0, errors.New("synthetic write error") }
func TestMTLSLogWriterErrors(t *testing.T) {
	writer := &redactingLogWriter{writer: mtlsFailWriter{}}
	if _, err := writer.Write([]byte("diagnostic\n")); err == nil {
		t.Fatal("write error hidden")
	}
}

func TestMTLSLogBeforeSetupKeepsSafeStderrDiagnostics(t *testing.T) {
	oldWriter, oldStderr := neko_log.LogWriter, os.Stderr
	file, err := os.CreateTemp(t.TempDir(), "early-log")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	neko_log.LogWriter, os.Stderr = nil, file
	defer func() { neko_log.LogWriter, os.Stderr = oldWriter, oldStderr }()
	writer := &redactingLogWriter{writer: nativeLogSink{}}
	if _, err = writer.Write([]byte("[Debug] client_key=early-secret; initialization failed\n")); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(file.Name())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "early-secret") || !strings.Contains(string(data), "[Debug]") || !strings.Contains(string(data), "initialization failed") {
		t.Fatal("Early diagnostic was lost or its sensitive value leaked")
	}
}
