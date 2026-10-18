package libcore

import (
	"io"
	"os"

	geosites "github.com/sagernet/sing-box/common/geosite"
	"github.com/sagernet/sing-box/option"
)

func loadGeoSiteRules(path, code string) ([]option.HeadlessRule, error) {
	input, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	return readGeoSiteRules(input, code)
}

func readGeoSiteRules(input io.ReadSeekCloser, code string) ([]option.HeadlessRule, error) {
	defer input.Close()
	reader, _, err := geosites.NewReader(input)
	if err != nil {
		return nil, err
	}
	source, err := reader.Read(code)
	if err != nil {
		return nil, err
	}
	compiled := geosites.Compile(source)
	return []option.HeadlessRule{{Type: "default", DefaultOptions: option.DefaultHeadlessRule{
		Domain: compiled.Domain, DomainSuffix: compiled.DomainSuffix, DomainKeyword: compiled.DomainKeyword, DomainRegex: compiled.DomainRegex,
	}}}, nil
}
