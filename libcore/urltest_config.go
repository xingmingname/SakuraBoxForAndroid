package libcore

import (
	"fmt"
	"reflect"
	"strings"

	"github.com/sagernet/sing-box/option"
)

// Inspect strictly decoded, migrated options before box.New creates auxiliary
// clients. Typed traversal includes named/inline HTTP clients and flattened
// protocol/rule variants; user map keys (e.g. headers) are not option fields.
// Reject conflicting settings instead of rewriting user configuration.
func validateTestNetworkConfig(options option.Options) error {
	if options.Route == nil || !options.Route.AutoDetectInterface {
		return fmt.Errorf("URL Test requires route.auto_detect_interface=true for independent network binding")
	}
	var walk func(reflect.Value, string) error
	walk = func(value reflect.Value, path string) error {
		for value.Kind() == reflect.Pointer || value.Kind() == reflect.Interface {
			if value.IsNil() {
				return nil
			}
			value = value.Elem()
		}
		switch value.Kind() {
		case reflect.Struct:
			typ := value.Type()
			for i := 0; i < value.NumField(); i++ {
				field := typ.Field(i)
				if !field.IsExported() {
					continue
				}
				child := value.Field(i)
				key := strings.Split(field.Tag.Get("json"), ",")[0]
				childPath := path
				// sing-box represents flattened variants using json:"-" fields.
				if key != "" && key != "-" {
					if childPath != "" {
						childPath += "."
					}
					childPath += key
				}
				switch key {
				case "bind_interface", "default_interface", "inet4_bind_address", "inet6_bind_address", "routing_mark", "default_mark", "protect_path", "netns":
					if !child.IsZero() {
						return fmt.Errorf("URL Test cannot safely combine pinned network with %s", childPath)
					}
				}
				if err := walk(child, childPath); err != nil {
					return err
				}
			}
		case reflect.Slice, reflect.Array:
			if value.Type().Elem().Kind() == reflect.Uint8 {
				return nil // RawMessage and binary data are not decoded options.
			}
			for i := 0; i < value.Len(); i++ {
				if err := walk(value.Index(i), fmt.Sprintf("%s[%d]", path, i)); err != nil {
					return err
				}
			}
		case reflect.Map:
			iter := value.MapRange()
			for iter.Next() {
				if err := walk(iter.Value(), fmt.Sprintf("%s[%v]", path, iter.Key())); err != nil {
					return err
				}
			}
		}
		return nil
	}
	return walk(reflect.ValueOf(options), "")
}
