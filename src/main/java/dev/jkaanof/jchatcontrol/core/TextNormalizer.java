package dev.jkaanof.jchatcontrol.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw chat text into a canonical form that is cheap to match against word lists.
 * <p>
 * Steps (all configurable): lower-case, Turkish/diacritic folding, homoglyph folding (Cyrillic/Greek look-alikes),
 * leetspeak decoding (4 -> a, 3 -> e, $ -> s ...) and collapsing of repeated letters ("siiiiktir" -> "siktir").
 * Word lists are normalized with the very same rules so both sides always line up.
 * <p>
 * Single pass over a char array, no regex: this runs for every chat message.
 */
public final class TextNormalizer {

    public static final class Options {
        public boolean leet = true;
        public boolean collapseRepeats = true;
        public boolean foldDiacritics = true;
        public boolean homoglyphs = true;
        public int singleLetterRunMin = 3;
    }

    private static final char[] FOLD = new char[0x0500];

    static {
        // Turkish
        map("ıIİi", 'i');
        map("ğĞ", 'g');
        map("üÜ", 'u');
        map("şŞ", 's');
        map("öÖ", 'o');
        map("çÇ", 'c');
        // Common latin diacritics (fast path, NFD is used for anything else)
        map("áàâäãåāăąÁÀÂÄÃÅĀĂĄ", 'a');
        map("éèêëēėęěÉÈÊËĒĖĘĚ", 'e');
        map("íìîïīįÍÌÎÏĪĮ", 'i');
        map("óòôõøōőÓÒÔÕØŌŐ", 'o');
        map("úùûūůűųÚÙÛŪŮŰŲ", 'u');
        map("ýÿÝŸ", 'y');
        map("ñńňÑŃŇ", 'n');
        map("śšŚŠ", 's');
        map("źżžŹŻŽ", 'z');
        map("ćčĆČ", 'c');
        map("ďĎ", 'd');
        map("łŁľĽĺĹ", 'l');
        map("řŘŕŔ", 'r');
        map("ťŤ", 't');
    }

    private static final char[] HOMO = new char[0x0500];

    static {
        homo("аАαΑ", 'a');
        homo("вВβΒ", 'b');
        homo("сСςϲ", 'c');
        homo("еЕεΕёЁ", 'e');
        homo("нНηΗ", 'h');
        homo("іІιΙїЇ", 'i');
        homo("јЈ", 'j');
        homo("кКκΚ", 'k');
        homo("мМμΜ", 'm');
        homo("пПπΠ", 'n');
        homo("оОοΟσ", 'o');
        homo("рРρΡ", 'p');
        homo("ѕЅ", 's');
        homo("тТτΤ", 't');
        homo("уУυΥ", 'y');
        homo("хХχΧ", 'x');
        homo("νΝ", 'v');
        homo("ωΩ", 'w');
        homo("ΖΖζ", 'z');
    }

    private static void map(String chars, char to) {
        for (int i = 0; i < chars.length(); i++) {
            FOLD[chars.charAt(i)] = to;
        }
    }

    private static void homo(String chars, char to) {
        for (int i = 0; i < chars.length(); i++) {
            HOMO[chars.charAt(i)] = to;
        }
    }

    private final Options options;

    public TextNormalizer(Options options) {
        this.options = options;
    }

    public Options options() {
        return options;
    }

    /** Leet / symbol decoding. Returns 0 if the char has no mapping. */
    static char leet(char c) {
        return switch (c) {
            case '4', '@' -> 'a';
            case '8' -> 'b';
            case '3', '€' -> 'e';
            case '6', '9' -> 'g';
            case '1', '!', '|' -> 'i';
            case '0' -> 'o';
            case '5', '$' -> 's';
            case '7', '+' -> 't';
            case '2' -> 'z';
            default -> 0;
        };
    }

    private static boolean isLeetSymbol(char c) {
        return c == '@' || c == '$' || c == '€' || c == '!' || c == '|' || c == '+';
    }

    /** Lower-case and fold a single character. Returns 0 if the char is not part of a word. */
    private char fold(char c) {
        if (c < 0x80) {
            if (c >= 'A' && c <= 'Z') {
                return (char) (c + 32);
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                return c;
            }
            return 0;
        }
        if (c < FOLD.length) {
            if (options.foldDiacritics && FOLD[c] != 0) {
                return FOLD[c];
            }
            if (options.homoglyphs && HOMO[c] != 0) {
                return HOMO[c];
            }
        }
        if (!Character.isLetterOrDigit(c)) {
            return 0;
        }
        char lower = Character.toLowerCase(c);
        if (options.foldDiacritics && lower > 0x7F) {
            String nfd = Normalizer.normalize(String.valueOf(lower), Normalizer.Form.NFD);
            char base = nfd.charAt(0);
            if (base < 0x80 && Character.isLetter(base)) {
                return base;
            }
        }
        return lower;
    }

    /** Normalizes a single word from a word list (same rules as message tokens). */
    public String normalizeWord(String word) {
        Normalized n = normalize(word);
        if (n.tokens().isEmpty()) {
            return "";
        }
        if (n.tokens().size() == 1) {
            return n.tokens().get(0).norm();
        }
        // multi word entry: join with space (phrase)
        return n.text();
    }

    public Normalized normalize(String raw) {
        final int len = raw.length();
        List<Token> tokens = new ArrayList<>(Math.max(4, len / 5));
        StringBuilder lowerRaw = new StringBuilder(len);
        StringBuilder text = new StringBuilder(len);
        StringBuilder norm = new StringBuilder(24);

        int tokenStart = -1;
        int totalLetters = 0;

        for (int i = 0; i <= len; i++) {
            char c = i < len ? raw.charAt(i) : ' ';
            lowerRaw.append(i < len ? Character.toLowerCase(c) : ' ');
            char f = fold(c);
            boolean symbol = f == 0 && options.leet && isLeetSymbol(c);
            boolean wordChar = f != 0 || symbol;

            if (wordChar) {
                if (tokenStart < 0) {
                    tokenStart = i;
                }
                continue;
            }
            if (tokenStart >= 0) {
                int end = i;
                // trim leading / trailing punctuation-like leet symbols ("hello!" -> "hello")
                int s = tokenStart;
                while (s < end && isEdgeSymbol(raw.charAt(s))) {
                    s++;
                }
                while (end > s && isEdgeSymbol(raw.charAt(end - 1))) {
                    end--;
                }
                if (s < end) {
                    Token t = buildToken(raw, s, end, norm);
                    if (t != null) {
                        tokens.add(t);
                        totalLetters += t.letters();
                        if (!text.isEmpty()) {
                            text.append(' ');
                        }
                        text.append(t.norm());
                    }
                }
                tokenStart = -1;
            }
        }
        lowerRaw.setLength(len);

        List<String> joins = singleLetterJoins(tokens);
        return new Normalized(raw, lowerRaw.toString(), tokens, text.toString(), joins, totalLetters);
    }

    private static boolean isEdgeSymbol(char c) {
        return c == '!' || c == '|' || c == '+';
    }

    private Token buildToken(String raw, int start, int end, StringBuilder norm) {
        norm.setLength(0);
        char prev = 0;
        int realLetters = 0;
        int realDigits = 0;
        for (int i = start; i < end; i++) {
            char c = raw.charAt(i);
            char f = fold(c);
            if (f != 0 && Character.isDigit(f)) {
                realDigits++;
            } else if (f != 0) {
                realLetters++;
            }
            if (options.leet) {
                char l = leet(f != 0 ? f : c);
                if (l != 0) {
                    f = l;
                }
            }
            if (f == 0) {
                continue;
            }
            if (options.collapseRepeats && f == prev) {
                continue;
            }
            norm.append(f);
            prev = f;
        }
        if (norm.isEmpty()) {
            return null;
        }
        // "123", "1v1", "x2" ... are neutral: never sent to AI and never whitelisted/blacklisted
        boolean neutral = realDigits > 0 && realLetters <= 1;
        String n = neutral ? raw.substring(start, end).toLowerCase(java.util.Locale.ROOT) : norm.toString();
        return new Token(raw.substring(start, end), start, end, n, neutral, realLetters);
    }

    private List<String> singleLetterJoins(List<Token> tokens) {
        List<String> joins = new ArrayList<>(0);
        int min = options.singleLetterRunMin;
        if (min <= 1) {
            return joins;
        }
        StringBuilder run = new StringBuilder();
        int count = 0;
        for (int i = 0; i <= tokens.size(); i++) {
            Token t = i < tokens.size() ? tokens.get(i) : null;
            if (t != null && !t.neutral() && t.norm().length() == 1) {
                run.append(t.norm());
                count++;
                continue;
            }
            if (count >= min) {
                joins.add(run.toString());
            }
            run.setLength(0);
            count = 0;
        }
        return joins;
    }

    /** One word of the message. */
    public record Token(String raw, int start, int end, String norm, boolean neutral, int letters) {
    }

    /**
     * Normalized message.
     *
     * @param raw      original message
     * @param lowerRaw lower-cased original (same length / indices as raw) for "raw" regex patterns
     * @param tokens   words
     * @param text     normalized words joined with single spaces (cache key + "normalized" regex target)
     * @param joins    runs of single letters glued together ("s i k" -> "sik") to catch spaced-out evasion
     * @param letters  total letter count
     */
    public record Normalized(String raw, String lowerRaw, List<Token> tokens, String text, List<String> joins,
                             int letters) {

        /** All tokens glued together without spaces ("a m k" / "a.m.k" / "am k"). */
        public String compact() {
            StringBuilder sb = new StringBuilder(text.length());
            for (Token t : tokens) {
                if (!t.neutral()) {
                    sb.append(t.norm());
                }
            }
            return sb.toString();
        }
    }
}
