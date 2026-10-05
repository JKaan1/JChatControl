package dev.jkaanof.jchatcontrol.bukkit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Built-in temporary mute, so the plugin works without any punishment plugin. */
public final class MuteManager {

    public record Mute(String name, long until, String reason) {
        public boolean expired() {
            return until > 0 && System.currentTimeMillis() >= until;
        }
    }

    private final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();
    private final File file;
    private volatile boolean dirty;

    public MuteManager(File file) {
        this.file = file;
    }

    public void load() {
        mutes.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("mutes");
        if (s == null) {
            return;
        }
        for (String key : s.getKeys(false)) {
            try {
                Mute m = new Mute(s.getString(key + ".name", "?"), s.getLong(key + ".until"), s.getString(key + ".reason", ""));
                if (!m.expired()) {
                    mutes.put(UUID.fromString(key), m);
                }
            } catch (IllegalArgumentException ignored) {
                // bad uuid
            }
        }
    }

    public void save() throws IOException {
        if (!dirty) {
            return;
        }
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        mutes.forEach((uuid, m) -> {
            if (!m.expired()) {
                y.set("mutes." + uuid + ".name", m.name());
                y.set("mutes." + uuid + ".until", m.until());
                y.set("mutes." + uuid + ".reason", m.reason());
            }
        });
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        y.save(file);
    }

    /** @param durationMs 0 = permanent */
    public void mute(UUID uuid, String name, long durationMs, String reason) {
        mutes.put(uuid, new Mute(name, durationMs <= 0 ? 0 : System.currentTimeMillis() + durationMs, reason));
        dirty = true;
    }

    public boolean unmute(UUID uuid) {
        boolean removed = mutes.remove(uuid) != null;
        dirty |= removed;
        return removed;
    }

    public Mute get(UUID uuid) {
        Mute m = mutes.get(uuid);
        if (m != null && m.expired()) {
            mutes.remove(uuid);
            dirty = true;
            return null;
        }
        return m;
    }

    /** "30s", "10m", "2h", "1d", "1w", combined "1h30m"; "perm" / "0" = permanent. Returns -1 if invalid. */
    public static long parseDuration(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        String l = s.toLowerCase(java.util.Locale.ROOT);
        if (l.equals("perm") || l.equals("permanent") || l.equals("0") || l.equals("kalici")) {
            return 0;
        }
        long total = 0;
        long num = -1;
        for (char c : l.toCharArray()) {
            if (Character.isDigit(c)) {
                num = (num < 0 ? 0 : num * 10) + (c - '0');
                continue;
            }
            if (num < 0) {
                return -1;
            }
            long unit = switch (c) {
                case 's' -> 1000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd', 'g' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> -1;
            };
            if (unit < 0) {
                return -1;
            }
            total += num * unit;
            num = -1;
        }
        if (num > 0) {
            total += num * 60_000L; // bare number = minutes
        }
        return total;
    }

    public static String format(long ms) {
        if (ms <= 0) {
            return "∞";
        }
        long s = ms / 1000;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("d ");
        }
        if (h > 0) {
            sb.append(h).append("h ");
        }
        if (m > 0) {
            sb.append(m).append("m ");
        }
        if (sb.isEmpty() || sec > 0 && d == 0) {
            sb.append(sec).append("s");
        }
        return sb.toString().trim();
    }
}
