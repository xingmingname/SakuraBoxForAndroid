package libcore

import (
	"bytes"
	geosites "github.com/sagernet/sing-box/common/geosite"
	"testing"
)

type trackedGeoReader struct {
	*bytes.Reader
	closed bool
}

func (r *trackedGeoReader) Close() error { r.closed = true; return nil }

func TestGeoAssetClosesOnSuccessAndFailure(t *testing.T) {
	var b bytes.Buffer
	if err := geosites.Write(&b, map[string][]geosites.Item{"test": {{Type: geosites.RuleTypeDomain, Value: "example.invalid"}}}); err != nil {
		t.Fatal(err)
	}
	for _, data := range [][]byte{b.Bytes(), []byte("invalid")} {
		input := &trackedGeoReader{Reader: bytes.NewReader(data)}
		rules, err := readGeoSiteRules(input, "test")
		if !input.closed {
			t.Fatal("Geo asset leaked")
		}
		if bytes.Equal(data, b.Bytes()) && (err != nil || len(rules) != 1 || len(rules[0].DefaultOptions.Domain) != 1) {
			t.Fatalf("Geo rules changed: %v", err)
		}
		if !bytes.Equal(data, b.Bytes()) && err == nil {
			t.Fatal("invalid asset accepted")
		}
	}
}
