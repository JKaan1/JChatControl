package dev.jkaanof.jchatcontrol.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Minimal JSON path: "results[0].category_scores.hate" */
public final class JsonPath {

    private JsonPath() {
    }

    public static JsonElement get(JsonElement root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return root;
        }
        JsonElement cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || cur.isJsonNull()) {
                return null;
            }
            String name = part;
            int bracket = part.indexOf('[');
            if (bracket >= 0) {
                name = part.substring(0, bracket);
            }
            if (!name.isEmpty()) {
                if (!cur.isJsonObject()) {
                    return null;
                }
                cur = cur.getAsJsonObject().get(name);
            }
            while (bracket >= 0 && cur != null) {
                int close = part.indexOf(']', bracket);
                if (close < 0) {
                    return null;
                }
                int index;
                try {
                    index = Integer.parseInt(part.substring(bracket + 1, close).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (!cur.isJsonArray()) {
                    return null;
                }
                JsonArray arr = cur.getAsJsonArray();
                cur = index >= 0 && index < arr.size() ? arr.get(index) : null;
                bracket = part.indexOf('[', close);
            }
        }
        return cur;
    }

    public static String getString(JsonElement root, String path) {
        JsonElement e = get(root, path);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        return e.toString();
    }

    public static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
