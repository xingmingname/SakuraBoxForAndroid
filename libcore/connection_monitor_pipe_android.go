package libcore

import (
	"os"
	"syscall"
)

// Android ParcelFileDescriptor pipes initially use blocking descriptors.
// Set O_NONBLOCK before os.NewFile so Go registers the descriptor with netpoll;
// Close and write deadlines can then interrupt a backpressured UI stream.
func monitorOpenPipe(fd int32) (*os.File, error) {
	if err := syscall.SetNonblock(int(fd), true); err != nil {
		_ = syscall.Close(int(fd))
		return nil, err
	}
	file := os.NewFile(uintptr(fd), "connection-monitor")
	if file == nil {
		_ = syscall.Close(int(fd))
		return nil, os.ErrInvalid
	}
	return file, nil
}
