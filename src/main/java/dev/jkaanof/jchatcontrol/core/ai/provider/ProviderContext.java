package dev.jkaanof.jchatcontrol.core.ai.provider;

import dev.jkaanof.jchatcontrol.core.ai.PromptBuilder;

import java.net.http.HttpClient;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Shared objects handed to every provider.
 *
 * @param http            one shared HTTP client (connection pooling / HTTP2 multiplexing)
 * @param prompt          system prompt builder
 * @param categories      enabled category ids
 * @param defaultCategory category used when a model reports something unknown
 * @param logger          plugin logger
 * @param debug           log requests / answers
 */
public record ProviderContext(HttpClient http, PromptBuilder prompt, Set<String> categories, String defaultCategory,
                              Logger logger, boolean debug) {
}
