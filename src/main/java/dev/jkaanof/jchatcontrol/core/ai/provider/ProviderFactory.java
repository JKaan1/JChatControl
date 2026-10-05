package dev.jkaanof.jchatcontrol.core.ai.provider;

import dev.jkaanof.jchatcontrol.core.ai.AiProvider;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.Locale;

public final class ProviderFactory {

    private ProviderFactory() {
    }

    public static AiProvider create(String name, Section cfg, ProviderContext ctx) {
        String type = cfg.getString("type", "openai").trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "ollama" -> new OllamaProvider(name, cfg, ctx);
            case "openai", "openai-compatible", "lmstudio", "lm-studio", "vllm", "llamacpp", "llama.cpp", "groq",
                 "openrouter", "deepseek", "mistral", "localai", "jan" -> new OpenAiCompatibleProvider(name, cfg, ctx);
            case "anthropic", "claude" -> new AnthropicProvider(name, cfg, ctx);
            case "gemini", "google" -> new GeminiProvider(name, cfg, ctx);
            case "openai-moderation", "moderation" -> new OpenAiModerationProvider(name, cfg, ctx);
            case "perspective" -> new PerspectiveProvider(name, cfg, ctx);
            case "custom", "http" -> new CustomHttpProvider(name, cfg, ctx);
            default -> throw new IllegalArgumentException("Unknown AI provider type '" + type + "' for provider '" + name + "'");
        };
    }
}
