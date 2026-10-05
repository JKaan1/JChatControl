package dev.jkaanof.jchatcontrol.core;

import dev.jkaanof.jchatcontrol.util.FileUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Remembers AI decisions per normalized message ("SA!!", "saaa" and "sa" share one entry), so the same
 * message is never sent to the AI twice. Bounded LRU, persisted to a TSV file between restarts.
 */
public final class DecisionCache {

    private record Entry(Verdict verdict, long time) {
    }

    private final int maxEntries;
    private final long cleanTtlMs;
    private final long flaggedTtlMs;
    private final Map<String, Entry> map;
    private volatile boolean dirty;

    public DecisionCache(int maxEntries, long cleanTtlMs, long flaggedTtlMs) {
        this.maxEntries = Math.max(100, maxEntries);
        this.cleanTtlMs = cleanTtlMs;
        this.flaggedTtlMs = flaggedTtlMs;
        this.map = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > DecisionCache.this.maxEntries;
            }
        };
    }

    public Verdict get(String key) {
        Entry e;
        synchronized (map) {
            e = map.get(key);
            if (e == null) {
                return null;
            }
            if (expired(e, System.currentTimeMillis())) {
                map.remove(key);
                dirty = true;
                return null;
            }
        }
        return e.verdict();
    }

    public void put(String key, Verdict verdict) {
        synchronized (map) {
            map.put(key, new Entry(verdict, System.currentTimeMillis()));
        }
        dirty = true;
    }

    public int removeIf(Predicate<Verdict> predicate) {
        int removed = 0;
        synchronized (map) {
            var it = map.values().iterator();
            while (it.hasNext()) {
                if (predicate.test(it.next().verdict())) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    public void clear() {
        synchronized (map) {
            map.clear();
        }
        dirty = true;
    }

    public int size() {
        synchronized (map) {
            return map.size();
        }
    }

    private boolean expired(Entry e, long now) {
        long ttl = e.verdict().flagged() ? flaggedTtlMs : cleanTtlMs;
        return ttl > 0 && now - e.time() > ttl;
    }

    // ------------------------------------------------------------------ persistence
    // format: time \t flagged(0/1) \t categories(,) \t words(,) \t confidence \t key

    public void load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        synchronized (map) {
            for (String line : lines) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] p = line.split("\t", 6);
                if (p.length < 6) {
                    continue;
                }
                try {
                    long time = Long.parseLong(p[0]);
                    boolean flagged = "1".equals(p[1]);
                    Verdict v = new Verdict(flagged, split(p[2]), split(p[3]), Verdict.Source.CACHE,
                            Double.parseDouble(p[4]), "cache");
                    Entry e = new Entry(v, time);
                    if (!expired(e, now)) {
                        map.put(p[5], e);
                    }
                } catch (NumberFormatException ignored) {
                    // skip corrupt line
                }
            }
        }
        dirty = false;
    }

    public void save(Path file) throws IOException {
        if (!dirty) {
            return;
        }
        List<Map.Entry<String, Entry>> snapshot;
        synchronized (map) {
            snapshot = new ArrayList<>(map.entrySet());
            dirty = false;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write("# JChatControl AI decision cache - time, flagged, categories, words, confidence, normalized message\n");
            for (Map.Entry<String, Entry> me : snapshot) {
                Verdict v = me.getValue().verdict();
                w.write(Long.toString(me.getValue().time()));
                w.write('\t');
                w.write(v.flagged() ? '1' : '0');
                w.write('\t');
                w.write(String.join(",", v.categories()));
                w.write('\t');
                w.write(join(v.words()));
                w.write('\t');
                w.write(Double.toString(v.confidence()));
                w.write('\t');
                w.write(me.getKey());
                w.write('\n');
            }
        }
        FileUtil.replace(tmp, file);
    }

    private static String join(List<String> words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            String clean = w.replace('\t', ' ').replace('\n', ' ').replace(',', ' ');
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(clean);
        }
        return sb.toString();
    }

    private static List<String> split(String s) {
        if (s.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(Arrays.asList(s.split(",")));
    }
}
