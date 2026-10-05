package dev.jkaanof.jchatcontrol.core;

import java.util.List;

/**
 * Result of checking a message.
 *
 * @param flagged    true if the message violates a rule
 * @param categories violated categories (ids from config), empty when clean
 * @param words      offending words as they were matched / reported (used for censoring and learning)
 * @param source     which stage decided
 * @param confidence 0..1 confidence (1 for local rules)
 * @param detail     human readable detail (matched word, pattern, provider name ...)
 */
public record Verdict(boolean flagged, List<String> categories, List<String> words, Source source,
                      double confidence, String detail) {

    public enum Source {
        /** Matched a blocked word list entry. */
        WORD_LIST,
        /** Matched a regex pattern. */
        PATTERN,
        /** Every word is in the allowed lists, no AI needed. */
        ALLOWED,
        /** Decision taken from the AI decision cache (no API call). */
        CACHE,
        /** Fresh AI decision. */
        AI,
        /** Skipped (too short, AI disabled, bypass ...). */
        SKIPPED,
        /** AI unavailable / timed out / rate limited, fallback policy applied. */
        FALLBACK
    }

    public static Verdict clean(Source source, String detail) {
        return new Verdict(false, List.of(), List.of(), source, 1.0, detail);
    }

    public static Verdict flagged(String category, List<String> words, Source source, double confidence, String detail) {
        return new Verdict(true, List.of(category), words, source, confidence, detail);
    }

    public Verdict withSource(Source newSource, String newDetail) {
        return new Verdict(flagged, categories, words, newSource, confidence, newDetail);
    }

    public String primaryCategory() {
        return categories.isEmpty() ? "unknown" : categories.get(0);
    }

    /** True if no AI call was made to reach this verdict. */
    public boolean savedApiCall() {
        return source != Source.AI;
    }
}
