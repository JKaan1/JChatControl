package dev.jkaanof.jchatcontrol.core.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;

/** Minimal Discord embed builder. Enforces Discord's length limits so a long chat message never breaks a post. */
public final class DiscordEmbed {

    private final JsonObject json = new JsonObject();
    private final JsonArray fields = new JsonArray();

    public DiscordEmbed title(String title) {
        if (title != null && !title.isBlank()) {
            json.addProperty("title", limit(title, 256));
        }
        return this;
    }

    public DiscordEmbed description(String text) {
        if (text != null && !text.isBlank()) {
            json.addProperty("description", limit(text, 4096));
        }
        return this;
    }

    /** "#E74C3C", "E74C3C" or a decimal number. */
    public DiscordEmbed color(String color) {
        Integer c = parseColor(color);
        if (c != null) {
            json.addProperty("color", c);
        }
        return this;
    }

    public DiscordEmbed field(String name, String value, boolean inline) {
        if (fields.size() >= 25 || name == null || value == null || value.isBlank()) {
            return this;
        }
        JsonObject f = new JsonObject();
        f.addProperty("name", limit(name.isBlank() ? "​" : name, 256));
        f.addProperty("value", limit(value, 1024));
        f.addProperty("inline", inline);
        fields.add(f);
        return this;
    }

    public DiscordEmbed thumbnail(String url) {
        if (url != null && url.startsWith("http")) {
            JsonObject t = new JsonObject();
            t.addProperty("url", url);
            json.add("thumbnail", t);
        }
        return this;
    }

    public DiscordEmbed footer(String text, String iconUrl) {
        if (text != null && !text.isBlank()) {
            JsonObject f = new JsonObject();
            f.addProperty("text", limit(text, 2048));
            if (iconUrl != null && iconUrl.startsWith("http")) {
                f.addProperty("icon_url", iconUrl);
            }
            json.add("footer", f);
        }
        return this;
    }

    public DiscordEmbed timestamp(Instant time) {
        json.addProperty("timestamp", time.toString());
        return this;
    }

    public JsonObject toJson() {
        JsonObject copy = json.deepCopy();
        if (!fields.isEmpty()) {
            copy.add("fields", fields.deepCopy());
        }
        return copy;
    }

    /** Plain text version (for the non-embed mode). */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        if (json.has("title")) {
            sb.append("**").append(json.get("title").getAsString()).append("**\n");
        }
        if (json.has("description")) {
            sb.append(json.get("description").getAsString()).append('\n');
        }
        for (var f : fields) {
            JsonObject o = f.getAsJsonObject();
            sb.append("**").append(o.get("name").getAsString()).append(":** ").append(o.get("value").getAsString()).append('\n');
        }
        return limit(sb.toString().trim(), 2000);
    }

    static Integer parseColor(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        String c = color.trim();
        try {
            if (c.startsWith("#")) {
                return Integer.parseInt(c.substring(1), 16);
            }
            if (c.length() == 6 && c.matches("[0-9a-fA-F]{6}") && !c.matches("\\d{6}")) {
                return Integer.parseInt(c, 16);
            }
            return Integer.parseInt(c);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Escapes Discord markdown so player text cannot format / break the embed. */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            if ("\\*_~`|>#[]()-".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c == '\n' ? ' ' : c);
        }
        return sb.toString();
    }
}
