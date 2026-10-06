package dev.jkaanof.jchatcontrol.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat delay + anti-spam. Runs BEFORE every filter stage, so flooded / repeated messages never reach the
 * word lists, the cache or the AI. Pure Java, thread-safe, O(history) per message.
 * <ul>
 *     <li><b>delay</b>: minimum time between two messages of a player</li>
 *     <li><b>duplicate</b>: same or very similar (normalized) message as one of the last N messages</li>
 *     <li><b>burst</b>: more than X messages within Y seconds</li>
 * </ul>
 */
public final class SpamGuard {

    public static final class Settings {
        public boolean enabled = true;
        public long delayMs = 1500;
        public boolean duplicateEnabled = true;
        public long duplicateWindowMs = 30_000;
        public int historySize = 3;
        /** 0..1, 1 = only identical messages are duplicates. */
        public double similarity = 0.85;
        /** Messages shorter than this (normalized) are only checked for identical repeats. */
        public int similarityMinLength = 6;
        public boolean burstEnabled = true;
        public int burstMessages = 5;
        public long burstWindowMs = 8_000;
        /** Spam blocks within this window that trigger the spam commands. */
        public int punishThreshold = 5;
        public long punishWindowMs = 60_000;
    }

    public enum Type { OK, DELAY, DUPLICATE, BURST }

    /**
     * @param type        result
     * @param remainingMs DELAY: how long the player still has to wait
     * @param punish      true when this block reached the punish threshold (run the spam commands)
     */
    public record Result(Type type, long remainingMs, boolean punish) {
        static final Result OK = new Result(Type.OK, 0, false);

        public boolean blocked() {
            return type != Type.OK;
        }
    }

    private static final class State {
        long lastMessage = Long.MIN_VALUE;
        final Deque<Long> sent = new ArrayDeque<>();            // accepted message times
        final Deque<String> recentText = new ArrayDeque<>();
        final Deque<Long> recentTime = new ArrayDeque<>();
        final Deque<Long> blocks = new ArrayDeque<>();
    }

    private final Settings settings;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public SpamGuard(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Checks and (when accepted) records a message.
     *
     * @param normalized normalized message text ({@link TextNormalizer.Normalized#text()})
     * @param checkDelay false to skip the delay check (e.g. player has a bypass permission)
     */
    public Result check(UUID player, String normalized, long now, boolean checkDelay) {
        if (!settings.enabled || player == null) {
            return Result.OK;
        }
        State st = states.computeIfAbsent(player, k -> new State());
        synchronized (st) {
            Result r = evaluate(st, normalized, now, checkDelay);
            if (r.blocked()) {
                return blocked(st, r.type(), r.remainingMs(), now);
            }
            st.lastMessage = now;
            st.sent.addLast(now);
            if (settings.historySize > 0) {
                st.recentText.addLast(normalized);
                st.recentTime.addLast(now);
                while (st.recentText.size() > settings.historySize) {
                    st.recentText.pollFirst();
                    st.recentTime.pollFirst();
                }
            }
            return Result.OK;
        }
    }

    private Result evaluate(State st, String text, long now, boolean checkDelay) {
        if (checkDelay && settings.delayMs > 0 && st.lastMessage != Long.MIN_VALUE) {
            long passed = now - st.lastMessage;
            if (passed < settings.delayMs) {
                return new Result(Type.DELAY, settings.delayMs - passed, false);
            }
        }
        if (settings.burstEnabled && settings.burstMessages > 0) {
            while (!st.sent.isEmpty() && now - st.sent.peekFirst() > settings.burstWindowMs) {
                st.sent.pollFirst();
            }
            if (st.sent.size() >= settings.burstMessages) {
                return new Result(Type.BURST, 0, false);
            }
        }
        if (settings.duplicateEnabled && !text.isEmpty()) {
            var texts = st.recentText.iterator();
            var times = st.recentTime.iterator();
            while (texts.hasNext()) {
                String old = texts.next();
                long time = times.next();
                if (now - time > settings.duplicateWindowMs) {
                    continue;
                }
                if (old.equals(text) || (settings.similarity < 1.0 && text.length() >= settings.similarityMinLength
                        && similarity(old, text) >= settings.similarity)) {
                    return new Result(Type.DUPLICATE, 0, false);
                }
            }
        }
        return Result.OK;
    }

    private Result blocked(State st, Type type, long remaining, long now) {
        st.blocks.addLast(now);
        while (!st.blocks.isEmpty() && now - st.blocks.peekFirst() > settings.punishWindowMs) {
            st.blocks.pollFirst();
        }
        boolean punish = settings.punishThreshold > 0 && st.blocks.size() >= settings.punishThreshold;
        if (punish) {
            st.blocks.clear();
        }
        return new Result(type, remaining, punish);
    }

    public void forget(UUID player) {
        states.remove(player);
    }

    /** 1 - normalized Levenshtein distance. Strings are capped at 128 chars. */
    static double similarity(String a, String b) {
        if (a.length() > 128) {
            a = a.substring(0, 128);
        }
        if (b.length() > 128) {
            b = b.substring(0, 128);
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        // cheap reject: length difference alone exceeds the allowed distance
        if (Math.abs(a.length() - b.length()) > max * 0.5) {
            return 0.0;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[b.length()] / max;
    }
}
