package libcore

import (
	"bytes"
	"strings"
	"testing"
)

func TestLogRedactionAtOutputBoundary(t *testing.T) {
	for _, message := range []string{`{"password":"test-secret","uuid":"test-secret","private_key":"test-secret"}`, "vless://test-secret@host.example:443?secret=test-secret#node", "Authorization=test-secret\npassword: test-secret", "-----BEGIN PRIVATE KEY-----\ntest-secret\n-----END PRIVATE KEY-----"} {
		var out bytes.Buffer
		writer := &redactingLogWriter{writer: &out}
		n, err := writer.Write([]byte(message))
		if flushErr := writer.Flush(); flushErr != nil {
			t.Fatal(flushErr)
		}
		if err != nil || n != len(message) || strings.Contains(out.String(), "test-secret") {
			t.Fatalf("secret leaked or writer contract broken: %q %v", out.String(), err)
		}
	}
	if redactLog("DNS timeout; port 53") != "DNS timeout; port 53" {
		t.Fatal("ordinary diagnostic changed")
	}
}
