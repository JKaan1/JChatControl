package dev.jkaanof.jchatcontrol.core;

import dev.jkaanof.jchatcontrol.core.ai.AiService;
import dev.jkaanof.jchatcontrol.core.learn.LearningManager;
import dev.jkaanof.jchatcontrol.core.match.PatternRule;
import dev.jkaanof.jchatcontrol.core.match.WordLists;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;

/**
 * The moderation pipeline, cheapest stage first. Only messages that survive every local stage AND contain
 * unknown or sensitive words are sent to the AI.
 *
 * <pre>
 *  1. normalize (leet, Turkish chars, repeats, homoglyphs)
 *  2. blocked words  (exact / prefix / contains + spaced-out letters)  -> flagged, no API call
 *  3. regex patterns (normalized + raw)                                  -> flagged, no API call
 *  4. AI decision cache (same normalized message seen before)           -> cached verdict, no API call
 *  5. allowed words: every word known and nothing suspicious            -> clean, no API call
 *  6. AI (batched, de-duplicated, rate limited, provider chain)         -> verdict, cached + learned
 * </pre>
 */
public final class FilterEngine {

    public enum TriggerMode {
        /** AI is asked when the message contains words that are not in the allowed lists (or suspicious ones). */
        UNKNOWN_WORDS,
        /** AI is asked only when a suspicious word is present. Lowest API usage. */
        SUSPICIOUS_ONLY,
        /** Every message that is not cached / locally flagged goes to the AI. */
        ALWAYS
    }

    public static final class Settings {
        public boolean checkSingleLetterRuns = true;
        public boolean checkCompact = false;
        public boolean aiEnabled = true;
        public TriggerMode triggerMode = TriggerMode.UNKNOWN_WORDS;
        public int minLetters = 3;
        public int maxUnknownWordsWithoutAi = 0;
        public boolean cacheEnabled = true;
    }

    /**
     * Outcome of {@link #evaluate}: either final right away, or pending on the AI.
     */
    public record Evaluation(TextNormalizer.Normalized normalized, Verdict verdict, CompletableFuture<Verdict> pending) {

        public boolean isFinal() {
            return pending == null;
        }
    }

    private final Settings settings;
    private final TextNormalizer normalizer;
    private final WordLists lists;
    private final List<PatternRule> patterns;
    private final Map<String, CategorySettings> categories;
    private final DecisionCache cache;
    private final AiService ai;
    private final LearningManager learning;
    private final Stats stats;

    public FilterEngine(Settings settings, TextNormalizer normalizer, WordLists lists, List<PatternRule> patterns,
                        Collection<CategorySettings> categories, DecisionCache cache, AiService ai,
                        LearningManager learning, Stats stats) {
        this.settings = settings;
        this.normalizer = normalizer;
        this.lists = lists;
        this.patterns = List.copyOf(patterns);
        this.categories = new LinkedHashMap<>();
        for (CategorySettings c : categories) {
            this.categories.put(c.id(), c);
        }
        this.cache = cache;
        this.ai = ai;
        this.learning = learning;
        this.stats = stats;
    }

    public Settings settings() {
        return settings;
    }

    public TextNormalizer normalizer() {
        return normalizer;
    }

    public WordLists lists() {
        return lists;
    }

    public DecisionCache cache() {
        return cache;
    }

    public AiService ai() {
        return ai;
    }

    public LearningManager learning() {
        return learning;
    }

    public Stats stats() {
        return stats;
    }

    public Map<String, CategorySettings> categories() {
        return categories;
    }

    public int patternCount() {
        return patterns.size();
    }

    public CategorySettings category(String id) {
        return categories.get(id);
    }

    private boolean enabled(String category) {
        CategorySettings c = categories.get(category);
        return c == null || c.enabled();
    }

    /**
     * Runs the pipeline.
     *
     * @param message raw message (color codes already stripped)
     * @param player  player for per-player AI limits (may be null)
     * @param allowAi false to only use local stages
     */
    public Evaluation evaluate(String message, UUID player, boolean allowAi) {
        return evaluate(normalizer.normalize(message), player, allowAi);
    }

    /** Same as {@link #evaluate(String, UUID, boolean)} for an already normalized message. */
    public Evaluation evaluate(TextNormalizer.Normalized n, UUID player, boolean allowAi) {
        String message = n.raw();
        Verdict local = checkLocal(n);
        if (local != null) {
            stats.record(local);
            return new Evaluation(n, local, null);
        }
        Verdict pre = beforeAi(n, player, allowAi);
        if (pre != null) {
            stats.record(pre);
            return new Evaluation(n, pre, null);
        }
        CompletableFuture<Verdict> future = ai.submit(n.text(), message).thenApply(v -> {
            stats.record(v);
            return v;
        });
        return new Evaluation(n, null, future);
    }

    /** Stages 2 + 3: word lists and patterns. Returns null if nothing matched. */
    public Verdict checkLocal(TextNormalizer.Normalized n) {
        // blocked words
        List<String> hits = null;
        String hitCategory = null;
        String detail = null;
        for (TextNormalizer.Token t : n.tokens()) {
            if (t.neutral()) {
                continue;
            }
            WordLists.Match m = lists.matchToken(t.norm());
            if (m != null && enabled(m.category())) {
                if (hits == null) {
                    hits = new ArrayList<>(2);
                    hitCategory = m.category();
                    detail = m.type().name().toLowerCase(Locale.ROOT) + ":" + m.word();
                }
                hits.add(t.raw());
            }
        }
        if (hits != null) {
            return new Verdict(true, List.of(hitCategory), hits, Verdict.Source.WORD_LIST, 1.0, detail);
        }
        if (settings.checkSingleLetterRuns) {
            for (String join : n.joins()) {
                WordLists.Match m = lists.matchJoined(join);
                if (m != null && enabled(m.category())) {
                    return new Verdict(true, List.of(m.category()), List.of(), Verdict.Source.WORD_LIST, 1.0,
                            "spaced:" + m.word());
                }
            }
        }
        if (settings.checkCompact) {
            String compact = n.compact();
            if (!compact.equals(n.text())) {
                WordLists.Match m = lists.matchJoined(compact);
                if (m != null && enabled(m.category())) {
                    return new Verdict(true, List.of(m.category()), List.of(), Verdict.Source.WORD_LIST, 1.0,
                            "compact:" + m.word());
                }
            }
        }
        // regex patterns
        for (PatternRule rule : patterns) {
            if (!enabled(rule.category())) {
                continue;
            }
            Matcher matcher = rule.pattern().matcher(rule.raw() ? n.lowerRaw() : n.text());
            if (matcher.find()) {
                List<String> words = rule.raw() ? List.of(n.raw().substring(matcher.start(), matcher.end())) : List.of();
                return new Verdict(true, List.of(rule.category()), words, Verdict.Source.PATTERN, 1.0,
                        "pattern:" + rule.pattern().pattern());
            }
        }
        return null;
    }

    /** Stages 4 + 5. Returns null when the AI must be asked. */
    private Verdict beforeAi(TextNormalizer.Normalized n, UUID player, boolean allowAi) {
        if (n.tokens().isEmpty()) {
            return Verdict.clean(Verdict.Source.SKIPPED, "empty");
        }
        if (settings.cacheEnabled) {
            Verdict cached = cache.get(n.text());
            if (cached != null) {
                return cached.withSource(Verdict.Source.CACHE, cached.detail());
            }
        }
        boolean suspicious = false;
        int unknown = 0;
        for (TextNormalizer.Token t : n.tokens()) {
            if (t.neutral()) {
                continue;
            }
            String w = t.norm();
            if (lists.isSuspicious(w)) {
                suspicious = true;
                break;
            }
            if (w.length() > 1 && !lists.isAllowed(w)) {
                unknown++;
            }
        }
        boolean needAi = switch (settings.triggerMode) {
            case ALWAYS -> true;
            case SUSPICIOUS_ONLY -> suspicious;
            case UNKNOWN_WORDS -> suspicious || unknown > settings.maxUnknownWordsWithoutAi;
        };
        if (!needAi) {
            return Verdict.clean(Verdict.Source.ALLOWED, unknown == 0 ? "all-known" : unknown + "-unknown");
        }
        if (!suspicious && n.letters() < settings.minLetters) {
            return Verdict.clean(Verdict.Source.SKIPPED, "too-short");
        }
        if (!allowAi || !settings.aiEnabled || ai == null || !ai.hasProviders()) {
            return Verdict.clean(Verdict.Source.SKIPPED, "ai-disabled");
        }
        if (!ai.tryAcquirePlayer(player)) {
            stats.rateLimited.increment();
            return ai.fallback("player-limit");
        }
        return null;
    }

    /** Called by the AI service for every fresh verdict: cache + learning. */
    public void onAiResult(String key, Verdict v) {
        if (settings.cacheEnabled) {
            cache.put(key, v);
        }
        if (learning != null) {
            learning.onAiVerdict(normalizer.normalize(key), v);
        }
    }

    /**
     * Replaces offending words with the censor char. Returns null if nothing could be censored
     * (e.g. pattern or context based violation) - the caller should block instead.
     */
    public String censor(TextNormalizer.Normalized n, List<String> words, char censorChar) {
        if (words.isEmpty()) {
            return null;
        }
        List<String> targets = new ArrayList<>(words.size());
        for (String w : words) {
            for (String part : w.split("\\s+")) {
                String norm = lists.normalize(part);
                if (!norm.isEmpty()) {
                    targets.add(norm);
                }
            }
        }
        StringBuilder sb = new StringBuilder(n.raw());
        boolean changed = false;
        for (TextNormalizer.Token t : n.tokens()) {
            for (String target : targets) {
                if (t.norm().equals(target) || (target.length() >= 3 && t.norm().contains(target))) {
                    for (int i = t.start(); i < t.end(); i++) {
                        sb.setCharAt(i, censorChar);
                    }
                    changed = true;
                    break;
                }
            }
        }
        // raw pattern matches (links ...) are reported as literal substrings
        if (!changed) {
            String lower = n.lowerRaw();
            for (String w : words) {
                int idx = w.isEmpty() ? -1 : lower.indexOf(w.toLowerCase(Locale.ROOT));
                if (idx >= 0) {
                    for (int i = idx; i < idx + w.length(); i++) {
                        sb.setCharAt(i, censorChar);
                    }
                    changed = true;
                }
            }
        }
        return changed ? sb.toString() : null;
    }

    /** Most severe action among the verdict's categories. */
    public Action actionFor(Verdict v, Action fallback) {
        Action best = null;
        for (String c : v.categories()) {
            CategorySettings cs = categories.get(c);
            Action a = cs == null ? fallback : cs.action();
            if (best == null || a.ordinal() > best.ordinal()) {
                best = a;
            }
        }
        return best == null ? fallback : best;
    }

    public int pointsFor(Verdict v) {
        int max = 0;
        for (String c : v.categories()) {
            CategorySettings cs = categories.get(c);
            if (cs != null) {
                max = Math.max(max, cs.points());
            }
        }
        return max;
    }
}
