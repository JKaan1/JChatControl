package dev.jkaanof.jchatcontrol.core.ai;

import java.util.List;

/**
 * Answer of an AI provider for one message.
 *
 * @param flagged    violates the rules
 * @param categories violated category ids
 * @param words      offending words (only generative providers report these)
 * @param score      0..1 probability of a violation (used for thresholds and escalation)
 * @param provider   name of the provider that answered
 */
public record AiResult(boolean flagged, List<String> categories, List<String> words, double score, String provider) {

    public AiResult withProvider(String name) {
        return new AiResult(flagged, categories, words, score, name);
    }
}
