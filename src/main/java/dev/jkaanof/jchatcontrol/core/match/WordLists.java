package dev.jkaanof.jchatcontrol.core.match;

import dev.jkaanof.jchatcontrol.core.TextNormalizer;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All word lists in normalized form.
 * <ul>
 *     <li><b>allowed</b>: known clean words. A message made only of allowed words never reaches the AI.</li>
 *     <li><b>blocked</b>: exact (whole word), prefix (word start, handles Turkish suffixes) and contains (substring).</li>
 *     <li><b>suspicious</b>: sensitive but not always bad words (religion, politics, ethnicity, ambiguous slang).
 *     They always force an AI check.</li>
 * </ul>
 * Base lists are immutable snapshots, learned / runtime words live in concurrent collections so the
 * chat threads never take a lock.
 */
public final class WordLists {

    public enum MatchType { EXACT, PREFIX, CONTAINS }

    public record Match(String word, String category, MatchType type, String token) {
    }

    public static final class StemOptions {
        public boolean enabled = true;
        public int minStemLength = 4;
        public int maxSuffixLength = 5;
    }

    private final TextNormalizer normalizer;
    private final StemOptions stem;

    // allowed
    private volatile Set<String> allowedBase = Set.of();
    private volatile PrefixTrie<Boolean> allowedTrie = new PrefixTrie<>();
    private final Set<String> allowedLearned = ConcurrentHashMap.newKeySet();
    private final Set<String> allowedRuntime = ConcurrentHashMap.newKeySet();

    // blocked
    private final Map<String, String> exact = new ConcurrentHashMap<>();
    private final Map<String, String> prefixWords = new ConcurrentHashMap<>();
    private final Map<String, String> containsWords = new ConcurrentHashMap<>();
    private volatile PrefixTrie<String> prefixTrie = new PrefixTrie<>();
    private volatile AhoCorasick contains = new AhoCorasick(Set.of());

    // suspicious
    private volatile PrefixTrie<Boolean> suspicious = new PrefixTrie<>();
    private volatile Set<String> suspiciousExact = Set.of();

    public WordLists(TextNormalizer normalizer, StemOptions stem) {
        this.normalizer = normalizer;
        this.stem = stem;
    }

    public String normalize(String word) {
        return normalizer.normalizeWord(word);
    }

    // ------------------------------------------------------------------ loading

    public void setAllowedBase(Collection<String> words) {
        Set<String> set = new HashSet<>(words.size() * 2);
        PrefixTrie<Boolean> trie = new PrefixTrie<>();
        for (String w : words) {
            // "iyi geceler" -> "iyi", "geceler"
            for (TextNormalizer.Token t : normalizer.normalize(w).tokens()) {
                if (!t.neutral()) {
                    set.add(t.norm());
                    trie.put(t.norm(), Boolean.TRUE);
                }
            }
        }
        allowedBase = set;
        allowedTrie = trie;
    }

    public void setAllowedLearned(Collection<String> words) {
        allowedLearned.clear();
        for (String w : words) {
            String n = normalize(w);
            if (!n.isEmpty()) {
                allowedLearned.add(n);
            }
        }
    }

    /** Clears blocked words. Call {@link #addBlocked} for each entry and then {@link #rebuild()}. */
    public void clearBlocked() {
        exact.clear();
        prefixWords.clear();
        containsWords.clear();
    }

    /** Adds a blocked word. Remember to call {@link #rebuild()} after PREFIX / CONTAINS additions. */
    public String addBlocked(String word, String category, MatchType type) {
        String n = normalize(word);
        if (n.isEmpty()) {
            return null;
        }
        switch (type) {
            case EXACT -> exact.put(n, category);
            case PREFIX -> prefixWords.put(n, category);
            case CONTAINS -> containsWords.put(n, category);
        }
        return n;
    }

    public boolean removeBlocked(String word) {
        String n = normalize(word);
        boolean removed = exact.remove(n) != null;
        removed |= prefixWords.remove(n) != null;
        removed |= containsWords.remove(n) != null;
        if (removed) {
            rebuild();
        }
        return removed;
    }

    /** Suspicious words match as word prefix ("allah" -> "allahım"); "=word" entries only match the whole word. */
    public void setSuspicious(Collection<String> words) {
        PrefixTrie<Boolean> trie = new PrefixTrie<>();
        Set<String> exactSet = new HashSet<>();
        for (String w : words) {
            boolean exactOnly = w.startsWith("=");
            String n = normalize(exactOnly ? w.substring(1) : w);
            if (n.isEmpty()) {
                continue;
            }
            if (exactOnly) {
                exactSet.add(n);
            } else {
                trie.put(n, Boolean.TRUE);
            }
        }
        suspicious = trie;
        suspiciousExact = exactSet;
    }

    /** Rebuilds the immutable automatons. Cheap enough to call on every manual change. */
    public void rebuild() {
        prefixTrie = new PrefixTrie<>(new HashMap<>(prefixWords));
        contains = new AhoCorasick(new HashSet<>(containsWords.keySet()));
    }

    // ------------------------------------------------------------------ allowed

    public boolean addAllowedLearned(String normalizedWord) {
        return allowedLearned.add(normalizedWord);
    }

    public boolean removeAllowedLearned(String normalizedWord) {
        return allowedLearned.remove(normalizedWord);
    }

    public Set<String> allowedLearned() {
        return allowedLearned;
    }

    public void addRuntimeAllowed(String word) {
        String n = normalize(word);
        if (!n.isEmpty()) {
            allowedRuntime.add(n);
        }
    }

    public void removeRuntimeAllowed(String word) {
        allowedRuntime.remove(normalize(word));
    }

    public boolean isExactlyAllowed(String norm) {
        return allowedBase.contains(norm) || allowedLearned.contains(norm) || allowedRuntime.contains(norm);
    }

    /** Allowed as-is or as an allowed stem + short suffix ("oyun" + "lar"). */
    public boolean isAllowed(String norm) {
        return allowedCoverLength(norm) > 0;
    }

    /**
     * Length of the allowed part of a token: the whole token if it is an allowed word, otherwise the length of
     * the longest allowed stem followed by a short suffix, otherwise 0.
     */
    private int allowedCoverLength(String norm) {
        if (isExactlyAllowed(norm)) {
            return norm.length();
        }
        if (!stem.enabled) {
            return 0;
        }
        int len = norm.length();
        if (len <= stem.minStemLength) {
            return 0;
        }
        int best = allowedTrie.longestPrefixLength(norm);
        if (best >= stem.minStemLength && len - best <= stem.maxSuffixLength) {
            return best;
        }
        if (!allowedLearned.isEmpty()) {
            int min = Math.max(stem.minStemLength, len - stem.maxSuffixLength);
            for (int k = len - 1; k >= min; k--) {
                if (allowedLearned.contains(norm.substring(0, k))) {
                    return k;
                }
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ blocked

    /**
     * Checks one normalized token against the blocked lists. Allowed words protect against
     * prefix / contains false positives (the "Scunthorpe problem": "sikayet" contains "sik"), but only when the
     * allowed word covers the whole bad word ("yara" + "k" must not whitelist "yarak"). Exact entries always win.
     */
    public Match matchToken(String norm) {
        String cat = exact.get(norm);
        if (cat != null) {
            return new Match(norm, cat, MatchType.EXACT, norm);
        }
        PrefixTrie<String> pt = prefixTrie;
        AhoCorasick ac = contains;
        if (pt.size() == 0 && ac.isEmpty()) {
            return null;
        }
        String p = pt.size() == 0 ? null : pt.shortestPrefixOf(norm);
        String c = p == null ? ac.findFirst(norm) : null;
        if (p == null && c == null) {
            return null;
        }
        int cover = allowedCoverLength(norm);
        if (p != null) {
            if (cover >= p.length()) {
                return null;
            }
            return new Match(p, prefixWords.getOrDefault(p, "profanity"), MatchType.PREFIX, norm);
        }
        if (cover > 0) {
            // every occurrence of every contained bad word must lie inside the allowed stem
            boolean outside = false;
            for (String w : ac.findAll(norm)) {
                int from = 0;
                int idx;
                while ((idx = norm.indexOf(w, from)) >= 0) {
                    if (idx + w.length() > cover) {
                        outside = true;
                        c = w;
                        break;
                    }
                    from = idx + 1;
                }
                if (outside) {
                    break;
                }
            }
            if (!outside) {
                return null;
            }
        }
        return new Match(c, containsWords.getOrDefault(c, "profanity"), MatchType.CONTAINS, norm);
    }

    /** Checks a glued string (spaced-out letters / compact form) against exact + contains lists. */
    public Match matchJoined(String joined) {
        String cat = exact.get(joined);
        if (cat != null) {
            return new Match(joined, cat, MatchType.EXACT, joined);
        }
        String p = prefixTrie.shortestPrefixOf(joined);
        if (p != null) {
            return new Match(p, prefixWords.getOrDefault(p, "profanity"), MatchType.PREFIX, joined);
        }
        String c = contains.findFirst(joined);
        if (c != null) {
            return new Match(c, containsWords.getOrDefault(c, "profanity"), MatchType.CONTAINS, joined);
        }
        return null;
    }

    public boolean isBlockedExact(String norm) {
        return exact.containsKey(norm);
    }

    // ------------------------------------------------------------------ suspicious

    public boolean isSuspicious(String norm) {
        return suspiciousExact.contains(norm) || suspicious.shortestPrefixOf(norm) != null;
    }

    // ------------------------------------------------------------------ stats

    public Map<String, Integer> sizes() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("allowed", allowedBase.size());
        m.put("allowed-learned", allowedLearned.size());
        m.put("blocked-exact", exact.size());
        m.put("blocked-prefix", prefixWords.size());
        m.put("blocked-contains", containsWords.size());
        m.put("suspicious", suspicious.size() + suspiciousExact.size());
        return m;
    }
}
