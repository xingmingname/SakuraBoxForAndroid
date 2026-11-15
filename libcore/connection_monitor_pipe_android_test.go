package libcore

import (
	"errors"
	"os"
	"syscall"
	"testing"
	"time"
)

func monitorAndroidPipe(t *testing.T) *os.File {
	t.Helper()
	fds := make([]int, 2)
	if err := syscall.Pipe(fds); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = syscall.Close(fds[0]) })
	file, err := monitorOpenPipe(int32(fds[1]))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = file.Close() })
	return file
}

func TestConnectionMonitorAndroidBackpressureClose(t *testing.T) {
	file := monitorAndroidPipe(t)
	done := make(chan error, 1)
	started := make(chan struct{})
	go func() {
		close(started)
		_, err := (monitorPipeWriter{file}).Write(make([]byte, 8*1024*1024))
		done <- err
	}()
	<-started
	time.Sleep(20 * time.Millisecond)
	select {
	case err := <-done:
		t.Fatalf("write did not remain backpressured before Close: %v", err)
	default:
	}
	closed := make(chan struct{})
	go func() { file.Close(); close(closed) }()
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("Android monitor Close blocked")
	}
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("backpressured write unexpectedly completed")
		}
	case <-time.After(time.Second):
		t.Fatal("Close did not interrupt the Android monitor pipe")
	}
}

func TestConnectionMonitorAndroidPipeDeadline(t *testing.T) {
	file := monitorAndroidPipe(t)
	if err := file.SetWriteDeadline(time.Now().Add(40 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := file.Write(make([]byte, 8*1024*1024)); done <- err }()
	select {
	case err := <-done:
		if !errors.Is(err, os.ErrDeadlineExceeded) {
			t.Fatalf("deadline not enforced: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Android pipe write deadline did not fire")
	}
}
