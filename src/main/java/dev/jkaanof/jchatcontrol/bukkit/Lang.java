package dev.jkaanof.jchatcontrol.bukkit;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Messages from lang/&lt;language&gt;.yml with &amp; color codes and &amp;#RRGGBB hex colors. */
public final class Lang {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    private final YamlConfiguration file;
    private final YamlConfiguration defaults;
    private final String prefix;

    public Lang(File langFile, InputStream defaultStream) {
        this.file = YamlConfiguration.loadConfiguration(langFile);
        this.defaults = defaultStream == null ? new YamlConfiguration()
                : YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
        this.prefix = color(raw("prefix"));
    }

    public static String color(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        Matcher m = HEX.matcher(s);
        if (m.find()) {
            StringBuilder sb = new StringBuilder();
            do {
                StringBuilder rep = new StringBuilder("&x");
                for (char c : m.group(1).toCharArray()) {
                    rep.append('&').append(c);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
            } while (m.find());
            m.appendTail(sb);
            s = sb.toString();
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String raw(String key) {
        String s = file.getString(key);
        if (s == null) {
            s = defaults.getString(key, key);
        }
        return s;
    }

    /** Colored message with placeholders: get("blocked", "category", "Küfür") */
    public String get(String key, Object... placeholders) {
        String s = raw(key).replace("{prefix}", prefix);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            s = s.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return color(s);
    }

    public List<String> getList(String key) {
        List<String> l = file.getStringList(key);
        if (l.isEmpty()) {
            l = defaults.getStringList(key);
        }
        return l.stream().map(s -> color(s.replace("{prefix}", prefix))).toList();
    }

    public void send(CommandSender to, String key, Object... placeholders) {
        String msg = get(key, placeholders);
        if (!msg.isEmpty()) {
            to.sendMessage(msg);
        }
    }
}
