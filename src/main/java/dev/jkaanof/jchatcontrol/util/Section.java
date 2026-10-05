package dev.jkaanof.jchatcontrol.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, platform independent view over a nested configuration map.
 * Keeps the core engine free of any Bukkit dependency (and unit-testable).
 */
public final class Section {

    private final Map<String, Object> values;

    public Section(Map<String, Object> values) {
        this.values = values == null ? Collections.emptyMap() : values;
    }

    public static Section empty() {
        return new Section(Collections.emptyMap());
    }

    public Map<String, Object> raw() {
        return values;
    }

    public boolean contains(String path) {
        return get(path) != null;
    }

    public Object get(String path) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            return values.get(path);
        }
        Object child = values.get(path.substring(0, dot));
        if (child instanceof Map<?, ?> map) {
            return new Section(cast(map)).get(path.substring(dot + 1));
        }
        return null;
    }

    public String getString(String path, String def) {
        Object o = get(path);
        return o == null ? def : String.valueOf(o);
    }

    public int getInt(String path, int def) {
        Object o = get(path);
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return def;
    }

    public long getLong(String path, long def) {
        Object o = get(path);
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return def;
    }

    public double getDouble(String path, double def) {
        Object o = get(path);
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return def;
    }

    public boolean getBoolean(String path, boolean def) {
        Object o = get(path);
        if (o instanceof Boolean b) {
            return b;
        }
        if (o != null) {
            String s = o.toString().trim();
            if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on")) {
                return true;
            }
            if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("no") || s.equalsIgnoreCase("off")) {
                return false;
            }
        }
        return def;
    }

    public List<String> getStringList(String path) {
        Object o = get(path);
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                if (e != null) {
                    out.add(String.valueOf(e));
                }
            }
        } else if (o != null && !o.toString().isBlank()) {
            out.add(o.toString());
        }
        return out;
    }

    public List<Object> getList(String path) {
        Object o = get(path);
        if (o instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>();
    }

    public Section getSection(String path) {
        Object o = get(path);
        if (o instanceof Map<?, ?> map) {
            return new Section(cast(map));
        }
        return empty();
    }

    /** Keys of this section, in declaration order. */
    public List<String> keys() {
        return new ArrayList<>(values.keySet());
    }

    /** String to string map (used for headers, category maps ...). */
    public Map<String, String> getStringMap(String path) {
        Map<String, String> out = new LinkedHashMap<>();
        Section s = getSection(path);
        for (String key : s.keys()) {
            Object v = s.values.get(key);
            if (v != null && !(v instanceof Map)) {
                out.put(key, String.valueOf(v));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
