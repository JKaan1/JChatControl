package dev.jkaanof.jchatcontrol.core.learn;

import dev.jkaanof.jchatcontrol.core.Stats;
import dev.jkaanof.jchatcontrol.core.TextNormalizer;
import dev.jkaanof.jchatcontrol.core.Verdict;
import dev.jkaanof.jchatcontrol.core.match.WordLists;
import dev.jkaanof.jchatcontrol.util.FileUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Turns AI decisions into local word list entries, so the next time no API call is needed.
 * <ul>
 *     <li>Clean AI verdict: every unknown word of the message gets a "clean sighting". After N sightings
 *     (in different messages) the word is added to {@code learned/allowed-words.txt}.</li>
 *     <li>Flagged AI verdict: the offending words reported by the model are added to
 *     {@code learned/blocked-words.txt} (or {@code learned/pending-words.txt} if approval is required).
 *     A word that was learned as allowed but is now reported as offensive is removed from the allowed list.</li>
 * </ul>
 */
public final class LearningManager {

    public static final class Settings {
        public boolean enabled = true;
        public boolean learnAllowed = true;
        public int allowedThreshold = 3;
        public boolean learnBlocked = true;
        public boolean requireApproval = false;
        public double blockedMinConfidence = 0.85;
        public int minWordLength = 3;
        public int maxWordLength = 24;
        public int maxCandidates = 50000;
    }

    /** Notified when the AI teaches a new blocked word (directly or as pending approval). */
    public interface Listener {
        void onLearned(String word, String category, boolean pending);
    }

    private volatile Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void notifyLearned(String word, String category, boolean pending) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onLearned(word, category, pending);
            } catch (RuntimeException e) {
                logger.warning("[Learning] listener failed: " + e.getMessage());
            }
        }
    }

    /** A learned / pending blocked word: normalized word + category. */
    public record LearnedWord(String word, String category) {
    }

    private final Settings settings;
    private final WordLists lists;
    private final Stats stats;
    private final Logger logger;
    private final Path allowedFile;
    private final Path blockedFile;
    private final Path pendingFile;
    private final Path candidatesFile;

    private final Map<String, AtomicInteger> candidates = new ConcurrentHashMap<>();
    private final Map<String, String> learnedBlocked = new ConcurrentHashMap<>();
    private final Map<String, String> pending = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public LearningManager(Settings settings, WordLists lists, Stats stats, Logger logger, Path dir) {
        this.settings = settings;
        this.lists = lists;
        this.stats = stats;
        this.logger = logger;
        this.allowedFile = dir.resolve("allowed-words.txt");
        this.blockedFile = dir.resolve("blocked-words.txt");
        this.pendingFile = dir.resolve("pending-words.txt");
        this.candidatesFile = dir.resolve("candidates.tsv");
    }

    public Settings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ load / save

    public void load() throws IOException {
        lists.setAllowedLearned(FileUtil.readWordList(allowedFile));
        learnedBlocked.clear();
        for (String line : FileUtil.readWordList(blockedFile)) {
            LearnedWord w = parseEntry(line);
            if (w != null) {
                learnedBlocked.put(w.word(), w.category());
                lists.addBlocked(w.word(), w.category(), WordLists.MatchType.EXACT);
            }
        }
        pending.clear();
        for (String line : FileUtil.readWordList(pendingFile)) {
            LearnedWord w = parseEntry(line);
            if (w != null) {
                pending.put(w.word(), w.category());
            }
        }
        candidates.clear();
        if (Files.exists(candidatesFile)) {
            for (String line : Files.readAllLines(candidatesFile, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    try {
                        candidates.put(line.substring(0, tab), new AtomicInteger(Integer.parseInt(line.substring(tab + 1).trim())));
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
            }
        }
        dirty = false;
    }

    /** "category:word" or just "word" (category profanity). */
    private static LearnedWord parseEntry(String line) {
        String l = line.trim();
        if (l.isEmpty()) {
            return null;
        }
        int colon = l.indexOf(':');
        if (colon > 0) {
            return new LearnedWord(l.substring(colon + 1).trim(), l.substring(0, colon).trim());
        }
        return new LearnedWord(l, "profanity");
    }

    public void save() throws IOException {
        if (!dirty) {
            return;
        }
        dirty = false;
        FileUtil.writeLines(allowedFile, """
                        JChatControl - learned allowed words (AI verified clean + /jcc allow).
                        One word per line. You can edit this file, then run /jcc reload.""",
                new TreeSet<>(lists.allowedLearned()));
        FileUtil.writeLines(blockedFile, """
                        JChatControl - learned blocked words (reported by the AI + /jcc block).
                        Format: category:word (exact word match after normalization). Edit freely, then /jcc reload.""",
                entries(learnedBlocked));
        FileUtil.writeLines(pendingFile, """
                        JChatControl - words reported by the AI waiting for staff approval (/jcc learn approve <word>).
                        Format: category:word""",
                entries(pending));
        List<String> cand = new ArrayList<>(candidates.size());
        candidates.forEach((k, v) -> cand.add(k + "\t" + v.get()));
        FileUtil.writeLines(candidatesFile, null, cand);
    }

    private static List<String> entries(Map<String, String> map) {
        Map<String, String> sorted = new TreeMap<>(map);
        List<String> out = new ArrayList<>(sorted.size());
        sorted.forEach((w, c) -> out.add(c + ":" + w));
        return out;
    }

    public void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------------ learning

    /** Called for every fresh AI verdict. */
    public void onAiVerdict(TextNormalizer.Normalized n, Verdict v) {
        if (!settings.enabled) {
            return;
        }
        if (v.flagged()) {
            learnFlagged(n, v);
        } else if (settings.learnAllowed) {
            learnClean(n);
        }
    }

    private void learnClean(TextNormalizer.Normalized n) {
        for (TextNormalizer.Token t : n.tokens()) {
            String w = t.norm();
            if (t.neutral() || !validWord(w) || lists.isExactlyAllowed(w) || lists.isSuspicious(w)
                    || lists.isBlockedExact(w) || pending.containsKey(w)) {
                continue;
            }
            if (candidates.size() >= settings.maxCandidates && !candidates.containsKey(w)) {
                continue;
            }
            int count = candidates.computeIfAbsent(w, k -> new AtomicInteger()).incrementAndGet();
            dirty = true;
            if (count >= settings.allowedThreshold) {
                candidates.remove(w);
                if (lists.addAllowedLearned(w)) {
                    stats.learnedAllowed.increment();
                }
            }
        }
    }

    private void learnFlagged(TextNormalizer.Normalized n, Verdict v) {
        String category = v.primaryCategory();
        for (String reported : v.words()) {
            for (String part : reported.split("\\s+")) {
                String w = lists.normalize(part);
                if (!validWord(w)) {
                    continue;
                }
                // the word must really occur in the message (protects against hallucinated words)
                boolean occurs = false;
                for (TextNormalizer.Token t : n.tokens()) {
                    if (t.norm().equals(w)) {
                        occurs = true;
                        break;
                    }
                }
                if (!occurs) {
                    continue;
                }
                candidates.remove(w);
                // AI changed its mind about a learned word: un-learn it
                if (lists.removeAllowedLearned(w)) {
                    logger.info("[Learning] '" + w + "' removed from learned allowed words (reported as " + category + ")");
                }
                dirty = true;
                if (!settings.learnBlocked || v.confidence() < settings.blockedMinConfidence
                        || lists.isExactlyAllowed(w) || learnedBlocked.containsKey(w)) {
                    continue;
                }
                if (settings.requireApproval) {
                    if (pending.putIfAbsent(w, category) == null) {
                        logger.info("[Learning] '" + w + "' (" + category + ") waits for approval: /jcc learn approve " + w);
                        notifyLearned(w, category, true);
                    }
                } else {
                    addBlocked(w, category);
                    stats.learnedBlocked.increment();
                    notifyLearned(w, category, false);
                }
            }
        }
    }

    private boolean validWord(String w) {
        if (w.length() < settings.minWordLength || w.length() > settings.maxWordLength) {
            return false;
        }
        for (int i = 0; i < w.length(); i++) {
            if (!Character.isLetter(w.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ manual management

    /** Adds a blocked word (exact match). Returns the normalized word. */
    public String addBlocked(String word, String category) {
        String n = lists.addBlocked(word, category, WordLists.MatchType.EXACT);
        if (n != null) {
            learnedBlocked.put(n, category);
            lists.removeAllowedLearned(n);
            pending.remove(n);
            candidates.remove(n);
            dirty = true;
        }
        return n;
    }

    public boolean removeBlocked(String word) {
        String n = lists.normalize(word);
        boolean removed = learnedBlocked.remove(n) != null;
        removed |= lists.removeBlocked(word);
        dirty = true;
        return removed;
    }

    public String addAllowed(String word) {
        String n = lists.normalize(word);
        if (n.isEmpty()) {
            return null;
        }
        lists.addAllowedLearned(n);
        candidates.remove(n);
        pending.remove(n);
        dirty = true;
        return n;
    }

    public boolean removeAllowed(String word) {
        boolean removed = lists.removeAllowedLearned(lists.normalize(word));
        dirty |= removed;
        return removed;
    }

    public Map<String, String> pending() {
        return new TreeMap<>(pending);
    }

    public boolean approve(String word) {
        String n = lists.normalize(word);
        String cat = pending.remove(n);
        if (cat == null) {
            return false;
        }
        addBlocked(n, cat);
        return true;
    }

    public boolean deny(String word) {
        String n = lists.normalize(word);
        boolean removed = pending.remove(n) != null;
        dirty |= removed;
        return removed;
    }

    public int learnedBlockedCount() {
        return learnedBlocked.size();
    }

    public int candidateCount() {
        return candidates.size();
    }
}
