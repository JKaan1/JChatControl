package dev.jkaanof.jchatcontrol.core.match;

import java.util.regex.Pattern;

/**
 * A compiled regex rule.
 *
 * @param category category id
 * @param pattern  compiled pattern
 * @param raw      true: matched against the lower-cased original message (links, IPs ...),
 *                 false: matched against the normalized text (leet decoded, Turkish chars folded, repeats collapsed)
 */
public record PatternRule(String category, Pattern pattern, boolean raw) {
}
