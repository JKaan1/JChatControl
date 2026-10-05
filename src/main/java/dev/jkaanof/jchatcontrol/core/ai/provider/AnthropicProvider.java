package dev.jkaanof.jchatcontrol.core.ai.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.jkaanof.jchatcontrol.core.ai.AiException;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Anthropic Messages API (Claude). Raw HTTP on purpose: the plugin talks to many vendors and shading a full
 * SDK (+ its HTTP / JSON stack) would make the jar many megabytes larger.
 * The system prompt is marked for prompt caching, so repeated requests only pay for the short user part.
 */
public final class AnthropicProvider extends ChatProvider {

    private final String url;
    private final String version;
    private final String effort;
    private final boolean cacheSystemPrompt;
    private final String fallbacks;

    public AnthropicProvider(String name, Section cfg, ProviderContext ctx) {
        super(name, cfg, ctx);
        this.url = trimSlash(cfg.getString("base-url", "https://api.anthropic.com/v1")) + "/messages";
        this.version = cfg.getString("anthropic-version", "2023-06-01");
        this.effort = cfg.getString("effort", "low");
        this.cacheSystemPrompt = cfg.getBoolean("cache-system-prompt", true);
        this.fallbacks = cfg.getString("fallbacks", "default");
    }

    @Override
    public String type() {
        return "anthropic";
    }

    @Override
    protected CompletableFuture<String> request(String system, String user, int tokens, boolean jsonMode) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        // models with always-on thinking need room for it; effort "low" keeps it short
        body.addProperty("max_tokens", Math.max(tokens, cfg.getInt("min-max-tokens", 1024)));
        if (system != null) {
            JsonArray sys = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", system);
            if (cacheSystemPrompt) {
                JsonObject cc = new JsonObject();
                cc.addProperty("type", "ephemeral");
                block.add("cache_control", cc);
            }
            sys.add(block);
            body.add("system", sys);
        }
        JsonArray messages = new JsonArray();
        JsonObject u = new JsonObject();
        u.addProperty("role", "user");
        u.addProperty("content", user);
        messages.add(u);
        body.add("messages", messages);
        if (!effort.isBlank() && !effort.equalsIgnoreCase("none")) {
            JsonObject oc = new JsonObject();
            oc.addProperty("effort", effort);
            body.add("output_config", oc);
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", version);
        if (!fallbacks.isBlank() && !fallbacks.equalsIgnoreCase("none")) {
            // on a safety-classifier refusal the API re-runs the request on a fallback model in the same call
            headers.put("anthropic-beta", "server-side-fallback-2026-07-01");
            body.addProperty("fallbacks", fallbacks);
        }
        return postJson(url, body.toString(), headers).thenApply(json -> {
            JsonObject o = json.getAsJsonObject();
            String stop = o.has("stop_reason") && !o.get("stop_reason").isJsonNull() ? o.get("stop_reason").getAsString() : "";
            if ("refusal".equals(stop)) {
                throw new AiException("Request refused by the model (stop_reason=refusal)");
            }
            StringBuilder sb = new StringBuilder();
            JsonElement content = o.get("content");
            if (content != null && content.isJsonArray()) {
                for (JsonElement b : content.getAsJsonArray()) {
                    JsonObject block = b.getAsJsonObject();
                    if (block.has("type") && "text".equals(block.get("type").getAsString())) {
                        sb.append(block.get("text").getAsString());
                    }
                }
            }
            if (sb.isEmpty()) {
                throw new AiException("No text in answer (stop_reason=" + stop + ")");
            }
            return sb.toString();
        });
    }
}
