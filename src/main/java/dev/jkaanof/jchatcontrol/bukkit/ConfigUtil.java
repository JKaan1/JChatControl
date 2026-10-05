package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.util.Section;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConfigUtil {

    private ConfigUtil() {
    }

    static Section toSection(ConfigurationSection cs) {
        return new Section(toMap(cs));
    }

    static Map<String, Object> toMap(ConfigurationSection cs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (cs == null) {
            return out;
        }
        for (String key : cs.getKeys(false)) {
            Object v = cs.get(key);
            out.put(key, convert(v));
        }
        return out;
    }

    private static Object convert(Object v) {
        if (v instanceof ConfigurationSection s) {
            return toMap(s);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), convert(val)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(convert(o));
            }
            return out;
        }
        return v;
    }
}
