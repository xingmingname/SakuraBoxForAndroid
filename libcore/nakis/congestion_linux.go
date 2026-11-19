//go:build linux

package nakis

import (
	"errors"
	"net"
	"syscall"

	"github.com/sagernet/sing/common"
)

func init() {
	setCongestion = func(conn net.Conn, name string) error {
		socket, ok := common.Cast[syscall.Conn](conn)
		if !ok {
			return errors.New("connection has no TCP socket")
		}
		raw, err := socket.SyscallConn()
		if err != nil {
			return err
		}
		var setErr error
		if err := raw.Control(func(fd uintptr) {
			setErr = syscall.SetsockoptString(int(fd), syscall.IPPROTO_TCP, syscall.TCP_CONGESTION, name)
		}); err != nil {
			return err
		}
		return setErr
	}
}
