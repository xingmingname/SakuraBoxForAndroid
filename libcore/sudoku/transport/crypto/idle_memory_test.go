package crypto

import (
	"net"
	"runtime"
	"testing"
)

// After bulk traffic in both directions, an idle record connection must hold
// no frame buffers: large frames are borrowed from framePool per call.
func TestIdleRecordConnHeap(t *testing.T) {
	const pairs = 100
	key := make([]byte, 32)
	payload := make([]byte, 256*1024)

	var before, after runtime.MemStats
	runtime.GC()
	runtime.GC()
	runtime.ReadMemStats(&before)

	var conns []*RecordConn
	stop := make(chan struct{})
	t.Cleanup(func() {
		close(stop)
		for _, conn := range conns {
			_ = conn.Close()
		}
	})
	for i := 0; i < pairs; i++ {
		a, b := net.Pipe()
		ra, err := NewRecordConn(a, "aes-128-gcm", key, key)
		if err != nil {
			t.Fatal(err)
		}
		rb, err := NewRecordConn(b, "aes-128-gcm", key, key)
		if err != nil {
			t.Fatal(err)
		}
		conns = append(conns, ra, rb)
		for _, pair := range [][2]*RecordConn{{ra, rb}, {rb, ra}} {
			written := make(chan struct{})
			go func(w *RecordConn) { _, _ = w.Write(payload); close(written) }(pair[0])
			// A small buffer forces the pending-plaintext path as well as direct reads.
			buf := make([]byte, 4096)
			for got := 0; got < len(payload); {
				n, err := pair[1].Read(buf)
				if err != nil {
					t.Fatal(err)
				}
				got += n
			}
			// Reading the last byte can precede the writer's deferred pool return.
			// Wait for the entire Write call before measuring an idle connection.
			<-written
		}
		for _, c := range []*RecordConn{ra, rb} {
			go func(c *RecordConn) {
				_, _ = c.Read(make([]byte, 1))
				<-stop
			}(c)
		}
	}
	runtime.GC()
	// The first GC moves sync.Pool buffers to the victim cache. A second GC
	// drops that cache, so this measures buffers retained by live connections
	// rather than per-P pool capacity (which depends on the host CPU count).
	runtime.GC()
	runtime.ReadMemStats(&after)
	perConn := float64(int64(after.HeapAlloc)-int64(before.HeapAlloc)) / float64(len(conns))
	runtime.KeepAlive(conns)
	t.Logf("idle heap per record conn: %.0f B", perConn)
	// Before pooling each side kept up to two 64 KiB frames. What remains is the
	// pair of AEAD states.
	if perConn > 8192 {
		t.Fatalf("idle record conn holds %.0f B, want <= 8192", perConn)
	}
}
