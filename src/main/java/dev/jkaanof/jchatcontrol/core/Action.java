package dev.jkaanof.jchatcontrol.core;

/** What happens to a message that violates a category. Ordered from least to most severe. */
public enum Action {
    /** Only log + notify staff. */
    LOG,
    /** Message is sent, player gets a warning. */
    WARN,
    /** Offending words are replaced with the censor character (falls back to BLOCK if words are unknown). */
    CENSOR,
    /** Message is not sent. */
    BLOCK;

    public static Action parse(String s, Action def) {
        if (s == null) {
            return def;
        }
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
