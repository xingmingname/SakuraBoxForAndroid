package nakis

import (
	"context"
	"net"

	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// Like the reference CLI, unsupported algorithms leave the system default in place.
var setCongestion func(net.Conn, string) error

type congestionDialer struct {
	N.Dialer
	name   string
	logger log.ContextLogger
}

func (d congestionDialer) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	conn, err := d.Dialer.DialContext(ctx, network, dst)
	if err != nil {
		return nil, err
	}
	// The injected dialer has already selected and protected the socket.
	if err := setCongestion(conn, d.name); err != nil {
		d.logger.WarnContext(ctx, "nakis: could not set TCP congestion control, using the system default: ", err)
	}
	return conn, nil
}
