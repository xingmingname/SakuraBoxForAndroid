package moe.matsuri.nb4a.utils;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.Map;
import java.util.Collection;
import java.util.IdentityHashMap;

/** Subscription input may only create YAML scalar, list and mapping values. */
public final class SafeYaml {
    private SafeYaml() {}

    private static final class SubscriptionConstructor extends SafeConstructor {
        SubscriptionConstructor(LoaderOptions options) {
            super(options);
            // Preserve the old parser's explicit !<str> tag, using only the
            // standard safe scalar constructor rather than class loading.
            yamlConstructors.put(new Tag("str"), yamlConstructors.get(Tag.STR));
        }
    }

    public static Map<?, ?> loadMap(String text) {
        if (text.length() > 8 * 1024 * 1024) {
            throw new YAMLException("YAML subscription exceeds 8 MiB character limit");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(50);
        try {
            DumperOptions dumper = new DumperOptions();
            Object value = new Yaml(new SubscriptionConstructor(options), new Representer(dumper), dumper, options).load(text);
            if (!(value instanceof Map)) throw new YAMLException("Subscription must be a YAML mapping");
            validateGraph(value, new IdentityHashMap<>(), 0);
            return (Map<?, ?>) value;
        } catch (StackOverflowError error) {
            throw new YAMLException("YAML subscription nesting is too deep", error);
        }
    }

    private static void validateGraph(Object value, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (!(value instanceof Map) && !(value instanceof Collection)) return;
        Boolean completed = visited.get(value);
        if (Boolean.FALSE.equals(completed)) throw new YAMLException("Recursive YAML values are not supported");
        if (Boolean.TRUE.equals(completed)) return;
        if (depth > 100) throw new YAMLException("YAML subscription nesting is too deep");
        visited.put(value, false);
        if (value instanceof Map) {
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                validateGraph(item.getKey(), visited, depth + 1);
                validateGraph(item.getValue(), visited, depth + 1);
            }
        } else {
            for (Object item : (Collection<?>) value) validateGraph(item, visited, depth + 1);
        }
        visited.put(value, true);
    }
}
