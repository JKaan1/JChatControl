package dev.jkaanof.jchatcontrol.core.ai.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.jkaanof.jchatcontrol.core.ai.AiException;
import dev.jkaanof.jchatcontrol.util.JsonPath;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Ollama native API (/api/chat). Keeps the model loaded with keep_alive and forces JSON output. */
public final class OllamaProvider extends ChatProvider {

    private final String url;
    private final String keepAlive;
    private final int numCtx;

    public OllamaProvider(String name, Section cfg, ProviderContext ctx) {
        super(name, cfg, ctx);
        this.url = trimSlash(cfg.getString("base-url", "http://localhost:11434")) + "/api/chat";
        this.keepAlive = cfg.getString("keep-alive", "30m");
        this.numCtx = cfg.getInt("num-ctx", 2048);
    }

    @Override
    public String type() {
        return "ollama";
    }

    @Override
    protected CompletableFuture<String> request(String system, String user, int tokens, boolean jsonMode) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("keep_alive", keepAlive);
        if (jsonMode) {
            body.addProperty("format", "json");
        }
        JsonArray messages = new JsonArray();
        if (system != null) {
            JsonObject s = new JsonObject();
            s.addProperty("role", "system");
            s.addProperty("content", system);
            messages.add(s);
        }
        JsonObject u = new JsonObject();
        u.addProperty("role", "user");
        u.addProperty("content", user);
        messages.add(u);
        body.add("messages", messages);
        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        options.addProperty("num_predict", tokens);
        options.addProperty("num_ctx", numCtx);
        body.add("options", options);
        // disable "thinking" on reasoning models (qwen3, deepseek-r1 ...) - we only need a short label
        if (cfg.getBoolean("disable-thinking", false)) {
            body.addProperty("think", false);
        }
        Map<String, String> headers = apiKey.isEmpty() ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
        return postJson(url, body.toString(), headers).thenApply(json -> {
            JsonElement content = JsonPath.get(json, "message.content");
            if (content == null || content.isJsonNull()) {
                throw new AiException("No content in answer: " + json);
            }
            return text(content);
        });
    }
}
