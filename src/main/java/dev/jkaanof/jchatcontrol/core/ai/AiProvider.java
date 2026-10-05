package dev.jkaanof.jchatcontrol.core.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A moderation backend: generative LLM (local or remote) or a dedicated decision / classifier model. */
public interface AiProvider {

    /** Name from config.yml. */
    String name();

    /** Provider type (ollama, openai, anthropic, gemini, openai-moderation, perspective, custom). */
    String type();

    /** How many messages can be checked in a single request (1 = no batching). */
    int maxBatch();

    /**
     * Classifies messages. The returned list has the same size and order as the input; an element may be
     * null if the provider gave no answer for that message (the next provider in the chain is then asked).
     */
    CompletableFuture<List<AiResult>> classify(List<String> messages);

    /** False while the circuit breaker is open (provider failed several times in a row). */
    boolean available();

    String status();
}
