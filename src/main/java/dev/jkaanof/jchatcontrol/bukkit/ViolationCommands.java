package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.core.Verdict;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Commands run on every violation, chosen by detection source (word list / regex / AI) and category.
 * Independent from the violation point thresholds.
 *
 * <pre>
 * violation-commands:
 *   enabled: true
 *   default:            # used when the category has no list for that source
 *     word-list: [...]
 *     regex: [...]
 *     ai: [...]
 *     cache: [...]      # optional, otherwise "ai" is used for cached AI decisions
 *   categories:
 *     racism:
 *       ai: [...]
 * </pre>
 */
public final class ViolationCommands {

    public enum Kind {
        WORD_LIST("word-list"), REGEX("regex"), AI("ai"), CACHE("cache");

        public final String key;

        Kind(String key) {
            this.key = key;
        }

        /** Maps a verdict source, null if the source never runs commands (fallback, clean ...). */
        public static Kind of(Verdict.Source source) {
            return switch (source) {
                case WORD_LIST -> WORD_LIST;
                case PATTERN -> REGEX;
                case AI -> AI;
                case CACHE -> CACHE;
                default -> null;
            };
        }
    }

    private final boolean enabled;
    private final Map<Kind, List<String>> defaults = new HashMap<>();
    private final Map<String, Map<Kind, List<String>>> categories = new HashMap<>();

    public ViolationCommands(Section cfg) {
        this.enabled = cfg.getBoolean("enabled", true);
        read(cfg.getSection("default"), defaults);
        Section cats = cfg.getSection("categories");
        for (String cat : cats.keys()) {
            Map<Kind, List<String>> m = new HashMap<>();
            read(cats.getSection(cat), m);
            categories.put(cat.toLowerCase(Locale.ROOT), m);
        }
    }

    public static ViolationCommands disabled() {
        return new ViolationCommands(new Section(Map.of("enabled", false)));
    }

    private static void read(Section s, Map<Kind, List<String>> into) {
        for (Kind k : Kind.values()) {
            // a present key (even an empty list) overrides the default; a missing key falls back
            if (s.contains(k.key)) {
                into.put(k, s.getStringList(k.key));
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** Commands for a verdict (all of its categories, duplicates removed). */
    public List<String> resolve(Verdict v) {
        if (!enabled || !v.flagged()) {
            return List.of();
        }
        Kind kind = Kind.of(v.source());
        if (kind == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String category : v.categories()) {
            out.addAll(resolve(category, kind));
        }
        return new ArrayList<>(out);
    }

    public List<String> resolve(String category, Kind kind) {
        Map<Kind, List<String>> cat = categories.get(category.toLowerCase(Locale.ROOT));
        List<String> list = lookup(cat, kind);
        if (list == null) {
            list = lookup(defaults, kind);
        }
        return list == null ? List.of() : list;
    }

    /** CACHE falls back to AI inside the same scope. */
    private static List<String> lookup(Map<Kind, List<String>> m, Kind kind) {
        if (m == null) {
            return null;
        }
        List<String> l = m.get(kind);
        if (l == null && kind == Kind.CACHE) {
            l = m.get(Kind.AI);
        }
        return l;
    }
}
