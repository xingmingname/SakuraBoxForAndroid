package sudoku

import "github.com/sagernet/sing-box/option"

const Type = "sudoku"
const UpstreamCommit = "36be4631668fd909452f46f90bbfed41c523cde3"

type HTTPMaskOptions struct {
	Disable   bool   `json:"disable"`
	Mode      string `json:"mode,omitempty"`
	TLS       bool   `json:"tls,omitempty"`
	Host      string `json:"host,omitempty"`
	PathRoot  string `json:"path_root,omitempty"`
	Multiplex string `json:"multiplex,omitempty"`
}

// Keep ordinary option structs so sing-box's strict decoder also checks nested
// HTTPMask fields. A pointer preserves the precedence of explicitly supplied
// nested options without changing legacy fields when httpmask is absent.
type OutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	Key                string           `json:"key"`
	AEADMethod         string           `json:"aead,omitempty"`
	PaddingMin         *int             `json:"padding_min,omitempty"`
	PaddingMax         *int             `json:"padding_max,omitempty"`
	ASCII              string           `json:"ascii,omitempty"`
	CustomTable        string           `json:"custom_table,omitempty"`
	CustomTables       []string         `json:"custom_tables,omitempty"`
	TableLimit         *uint64          `json:"table_limit,omitempty"`
	EnablePureDownlink *bool            `json:"enable_pure_downlink,omitempty"`
	Multiplex          string           `json:"multiplex,omitempty"`
	DisableHTTPMask    bool             `json:"disable_http_mask,omitempty"`
	HTTPMaskMode       string           `json:"http_mask_mode,omitempty"`
	HTTPMaskMultiplex  string           `json:"http_mask_multiplex,omitempty"`
	HTTPMaskPathRoot   string           `json:"http_mask_path_root,omitempty"`
	HTTPMaskTLS        bool             `json:"http_mask_tls,omitempty"`
	HTTPMaskHost       string           `json:"http_mask_host,omitempty"`
	HTTPMaskStrategy   string           `json:"http_mask_strategy,omitempty"`
	PathRoot           string           `json:"path_root,omitempty"`
	HTTPMask           *HTTPMaskOptions `json:"httpmask,omitempty"`
}

func (o OutboundOptions) resolved() OutboundOptions {
	if hm := o.HTTPMask; hm != nil {
		o.DisableHTTPMask = hm.Disable
		o.HTTPMaskMode = hm.Mode
		o.HTTPMaskMultiplex = hm.Multiplex
		o.HTTPMaskTLS = hm.TLS
		o.HTTPMaskHost = hm.Host
		o.HTTPMaskPathRoot = hm.PathRoot
	} else if o.HTTPMaskPathRoot == "" {
		o.HTTPMaskPathRoot = o.PathRoot
	}
	return o
}
