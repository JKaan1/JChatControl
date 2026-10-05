package dev.jkaanof.jchatcontrol.bukkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Violation points per player. Points expire after the decay time; crossing a threshold returns the
 * commands configured for it (mute, kick, ban ... from any plugin).
 */
public final class ViolationManager {

    private record Entry(long time, int points) {
    }

    private final long decayMs;
    private final NavigableMap<Integer, List<String>> thresholds;
    private final Map<UUID, Deque<Entry>> data = new ConcurrentHashMap<>();

    public ViolationManager(long decayMs, Map<Integer, List<String>> thresholds) {
        this.decayMs = decayMs;
        this.thresholds = new TreeMap<>(thresholds);
    }

    /** Adds points and returns the commands of every threshold crossed by this addition. */
    public List<String> add(UUID player, int points) {
        if (points <= 0) {
            return List.of();
        }
        Deque<Entry> d = data.computeIfAbsent(player, k -> new ArrayDeque<>());
        int before;
        int after;
        synchronized (d) {
            purge(d);
            before = sum(d);
            d.addLast(new Entry(System.currentTimeMillis(), points));
            after = before + points;
        }
        List<String> commands = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> e : thresholds.subMap(before, false, after, true).entrySet()) {
            commands.addAll(e.getValue());
        }
        return commands;
    }

    public int points(UUID player) {
        Deque<Entry> d = data.get(player);
        if (d == null) {
            return 0;
        }
        synchronized (d) {
            purge(d);
            return sum(d);
        }
    }

    public void reset(UUID player) {
        data.remove(player);
    }

    private void purge(Deque<Entry> d) {
        if (decayMs <= 0) {
            return;
        }
        long limit = System.currentTimeMillis() - decayMs;
        while (!d.isEmpty() && d.peekFirst().time() < limit) {
            d.pollFirst();
        }
    }

    private static int sum(Deque<Entry> d) {
        int s = 0;
        for (Entry e : d) {
            s += e.points();
        }
        return s;
    }
}
