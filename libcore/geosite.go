package libcore

import (
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
	"path/filepath"
)

func init() {
	nekoutils.GetGeoSiteHeadlessRules = func(name string) ([]option.HeadlessRule, error) {
		return loadGeoSiteRules(filepath.Join(externalAssetsPath, "geosite.db"), name)
	}
}
