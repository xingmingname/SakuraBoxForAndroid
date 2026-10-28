//go:build with_utls

package libcore

import (
	"context"
	"crypto/tls"
	"reflect"
	"testing"

	btls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
)

func TestCompatTLSCurveConstructorCapabilities(t *testing.T) {
	for _, mode := range []string{"standard", "utls", "reality"} {
		t.Run(mode, func(t *testing.T) {
			extra := ""
			if mode != "standard" {
				extra = `,"utls":{"enabled":true,"fingerprint":"chrome"}`
			}
			if mode == "reality" {
				extra += `,"reality":{"enabled":true,"public_key":"BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc","short_id":"00"}`
			}
			var opts option.OutboundTLSOptions
			if err := sjson.Unmarshal([]byte(`{"enabled":true,"server_name":"example.invalid","curve_preferences":["P256"]`+extra+`}`), &opts); err != nil {
				t.Fatal(err)
			}
			cfg, err := btls.NewClient(context.Background(), log.NewNOPFactory().Logger(), "example.invalid", opts)
			if err != nil {
				t.Fatal(err)
			}
			if mode == "standard" {
				std, err := cfg.STDConfig()
				if err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(std.CurvePreferences, []tls.CurveID{tls.CurveP256}) {
					t.Fatal("Standard TLS lost its explicit curve")
				}
			} else {
				value := reflect.ValueOf(cfg).Elem()
				if mode == "reality" {
					value = value.FieldByName("uClient").Elem()
				}
				curves := value.FieldByName("config").Elem().FieldByName("CurvePreferences")
				if curves.Len() != 0 {
					t.Fatal("Locked core changed; review the UI capability restriction")
				}
			}
		})
	}
}
