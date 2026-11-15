package tunnel

import (
	"bytes"
	"errors"
	"io"
	"net"
	"os"
	"sync"
	"testing"
	"time"
)

type gatedMuxWriter struct {
	net.Conn
	blocked chan struct{}
	resume  chan struct{}
	once    sync.Once
}

func (c *gatedMuxWriter) Write(p []byte) (int, error) {
	if len(p) > muxCoalesceMaxPayload {
		c.once.Do(func() { close(c.blocked) })
		<-c.resume
	}
	return c.Conn.Write(p)
}

func TestMuxAbortInterruptsOnlySelectedStream(t *testing.T) {
	a, b := net.Pipe()
	raw := &gatedMuxWriter{Conn: a, blocked: make(chan struct{}), resume: make(chan struct{})}
	var resumeOnce sync.Once
	resume := func() { resumeOnce.Do(func() { close(raw.resume) }) }
	defer resume()
	opened := make(chan *muxStream, 2)
	peer := newMuxSession(b, func(st *muxStream, _ []byte) { opened <- st })
	defer peer.closeWithError(net.ErrClosed)
	local := newMuxSession(raw, nil)
	defer local.closeWithError(net.ErrClosed)
	dead, err := openMuxStream(local, nil)
	if err != nil {
		t.Fatal(err)
	}
	peerDead := <-opened
	other, err := openMuxStream(local, nil)
	if err != nil {
		t.Fatal(err)
	}
	peerOther := <-opened
	st := dead.(*muxStream)
	st.EnableStreamCancellation()
	write := make(chan error, 1)
	go func() {
		_, err := st.Write(bytes.Repeat([]byte{1}, 8*muxMaxDataPayload))
		write <- err
	}()
	select {
	case <-raw.blocked:
	case <-time.After(time.Second):
		t.Fatal("shared writer did not stall")
	}
	if err := st.Abort(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-write:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("write cancellation: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("stream write still waiting for the shared transport")
	}
	if local.isClosed() {
		t.Fatal("canceling one stream closed the transport")
	}
	resume()
	_ = peerDead.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := io.Copy(io.Discard, peerDead); err == nil || errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatalf("peer did not receive the stream reset: %v", err)
	}
	if _, err := other.Write([]byte("still open")); err != nil {
		t.Fatal(err)
	}
	_ = peerOther.SetReadDeadline(time.Now().Add(time.Second))
	p := make([]byte, len("still open"))
	if _, err := io.ReadFull(peerOther, p); err != nil || string(p) != "still open" {
		t.Fatalf("sibling stream: %q %v", p, err)
	}
}
