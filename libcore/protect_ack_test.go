package libcore

import (
	"bytes"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

func TestProtectAcknowledgement(t *testing.T) {
	for _, value := range []byte{0, 1, 2, 255} {
		err := readProtectAck(bytes.NewReader([]byte{value}))
		if (err == nil) != (value == 1) {
			t.Fatalf("ack=%d error=%v", value, err)
		}
	}
	if err := readProtectAck(bytes.NewReader(nil)); !errors.Is(err, io.EOF) {
		t.Fatalf("EOF not preserved: %v", err)
	}
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetReadDeadline(time.Now().Add(20 * time.Millisecond))
	var timeout net.Error
	if err := readProtectAck(client); !errors.As(err, &timeout) || !timeout.Timeout() {
		t.Fatalf("timeout not preserved: %v", err)
	}
}
