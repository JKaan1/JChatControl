package dev.jkaanof.jchatcontrol.core.ai.provider;

import com.google.gson.JsonElement;
import dev.jkaanof.jchatcontrol.core.ai.AiResult;
import dev.jkaanof.jchatcontrol.core.ai.PromptBuilder;
import dev.jkaanof.jchatcontrol.core.ai.ResponseParser;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for providers that talk to a text generating model (LLMs and generative decision models
 * such as Llama Guard / ShieldGemma served through Ollama, LM Studio, vLLM ...).
 */
public abstract class ChatProvider extends AbstractProvider {

    protected final String model;
    protected final ResponseParser parser;
    protected final boolean useSystemPrompt;
    protected final int maxTokens;
    protected final double temperature;
    private final int maxBatch;

    protected ChatProvider(String name, Section cfg, ProviderContext ctx) {
        super(name, cfg, ctx);
        this.model = cfg.getString("model", "");
        ResponseParser.Mode mode = parseMode(cfg.getString("parser", "json"));
        this.parser = new ResponseParser(mode, ctx.categories(), cfg.getString("default-category", ctx.defaultCategory()),
                cfg.getStringMap("category-map"), cfg.getStringList("flag-labels"));
        this.useSystemPrompt = cfg.getBoolean("use-system-prompt", mode == ResponseParser.Mode.JSON);
        this.maxTokens = Math.max(8, cfg.getInt("max-tokens", mode == ResponseParser.Mode.JSON ? 160 : 16));
        this.temperature = cfg.getDouble("temperature", 0.0);
        this.maxBatch = parser.supportsBatch() ? Math.max(1, cfg.getInt("max-batch", 10)) : 1;
    }

    static ResponseParser.Mode parseMode(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "guard", "llama-guard", "safe-unsafe" -> ResponseParser.Mode.GUARD;
            case "yes-no", "yesno", "shieldgemma" -> ResponseParser.Mode.YES_NO;
            case "label", "labels", "classifier" -> ResponseParser.Mode.LABEL;
            default -> ResponseParser.Mode.JSON;
        };
    }

    @Override
    public int maxBatch() {
        return maxBatch;
    }

    @Override
    public CompletableFuture<List<AiResult>> classify(List<String> messages) {
        if (parser.supportsBatch()) {
            String system = useSystemPrompt ? ctx.prompt().systemPrompt() : null;
            String user = PromptBuilder.userContent(messages);
            // the answer grows with the number of messages
            int tokens = Math.max(maxTokens, 24 + 40 * messages.size());
            return track(request(system, user, tokens, true)
                    .thenApply(text -> parser.parse(text, messages.size(), name)));
        }
        String system = useSystemPrompt ? ctx.prompt().systemPrompt() : null;
        return track(eachSingle(messages, m -> request(system, m, maxTokens, false)
                .thenApply(text -> parser.parse(text, 1, name).get(0))));
    }

    /**
     * Sends one chat request and returns the answer text.
     *
     * @param system   system prompt or null
     * @param user     user content
     * @param tokens   max output tokens
     * @param jsonMode ask the backend for a JSON answer if it supports it
     */
    protected abstract CompletableFuture<String> request(String system, String user, int tokens, boolean jsonMode);

    protected static String text(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "";
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }
}
