package libcore

import (
	"context"
	"fmt"
	"libcore/nakis"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/option"
)

// CheckConfig strictly decodes an internal configuration without constructing
// services, opening sockets or imposing official-client export restrictions.
// Sudoku resource preflight uses bounded hint workspace, not complete tables.
func CheckConfig(config string) error {
	data, _, err := prepareConfig(config)
	if err != nil {
		return err
	}
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), certificate.NewRegistry())
	var options option.Options
	if err = options.UnmarshalJSONContext(ctx, data); err != nil {
		return err
	}
	for i, outbound := range options.Outbounds {
		if cfg, ok := outbound.Options.(*nakis.OutboundOptions); ok {
			if err := nakis.ValidateOptions(*cfg); err != nil {
				return fmt.Errorf("outbounds[%d]: %w", i, err)
			}
		}
	}
	return validateSudokuResources(options)
}
