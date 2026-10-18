package moe.matsuri.nb4a.utils;

import org.junit.Test;
import org.yaml.snakeyaml.error.YAMLException;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class SafeYamlTest {
    @Test public void recursiveValuesFailButSharedAnchorsRemainSupported() {
        // SafeConstructor alone accepts this cyclic value graph.
        Object cyclic = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor()).load("proxies: &loop [*loop]");
        List<?> list = (List<?>) ((Map<?, ?>) cyclic).get("proxies");
        assertSame(list, list.get(0));
        try { SafeYaml.loadMap("proxies: &loop [*loop]"); fail("Cycle accepted"); } catch (YAMLException expected) {}
        assertEquals(2, ((List<?>) SafeYaml.loadMap("defaults: &d {tls: true}\nproxies: [*d, *d]").get("proxies")).size());
    }
    @Test public void normalClashSubscriptionRetainsTypes() {
        Map<?, ?> root = SafeYaml.loadMap("global-client-fingerprint: chrome\nproxies:\n  - name: node\n    type: vless\n    port: 443\n    tls: true\n    password: !!str 12345\n");
        Map<?, ?> node = (Map<?, ?>) ((List<?>) root.get("proxies")).get(0);
        assertEquals(443, node.get("port")); assertEquals(true,node.get("tls")); assertEquals("12345",node.get("password"));
        assertEquals("12345",SafeYaml.loadMap("password: !<str> 12345").get("password"));
    }
    @Test public void javaTagsCannotInstantiateClasses() {
        for (String input : new String[]{"!!java.util.Date {}", "proxies: [!!java.net.URLClassLoader []]", "!!javax.script.ScriptEngineManager []", "proxies: []\nproxies: []", "[1,2]"}) {
            try { SafeYaml.loadMap(input); fail("Unsafe YAML accepted: " + input); } catch (YAMLException expected) {}
        }
    }
}
