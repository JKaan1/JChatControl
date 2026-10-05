package dev.jkaanof.jchatcontrol.core.ai.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.jkaanof.jchatcontrol.core.ai.AiException;
import dev.jkaanof.jchatcontrol.util.JsonPath;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Any OpenAI compatible /v1/chat/completions endpoint: OpenAI, Groq, OpenRouter, DeepSeek, Mistral, Together,
 * xAI, LM Studio, vLLM, llama.cpp server, LocalAI, Jan, KoboldCpp, text-generation-webui, Ollama (/v1) ...
 */
public final class OpenAiCompatibleProvider extends ChatProvider {

    private final String url;
    private final boolean jsonResponseFormat;
    private final String maxTokensField;
    private final JsonObject extraBody;

    public OpenAiCompatibleProvider(String name, Section cfg, ProviderContext ctx) {
        super(name, cfg, ctx);
        this.url = trimSlash(cfg.getString("base-url", "https://api.openai.com/v1")) + "/chat/completions";
        this.jsonResponseFormat = cfg.getBoolean("json-response-format", true);
        this.maxTokensField = cfg.getString("max-tokens-field", "max_tokens");
        String extra = cfg.getString("extra-body", "");
        this.extraBody = extra.isBlank() ? new JsonObject() : JsonParser.parseString(extra).getAsJsonObject();
    }

    @Override
    public String type() {
        return "openai";
    }

    @Override
    protected CompletableFuture<String> request(String system, String user, int tokens, boolean jsonMode) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
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
        body.addProperty("temperature", temperature);
        body.addProperty(maxTokensField, tokens);
        body.addProperty("stream", false);
        if (jsonMode && jsonResponseFormat) {
            JsonObject rf = new JsonObject();
            rf.addProperty("type", "json_object");
            body.add("response_format", rf);
        }
        for (Map.Entry<String, JsonElement> e : extraBody.entrySet()) {
            body.add(e.getKey(), e.getValue());
        }
        Map<String, String> headers = new HashMap<>();
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return postJson(url, body.toString(), headers).thenApply(json -> {
            JsonElement content = JsonPath.get(json, "choices[0].message.content");
            if (content == null || content.isJsonNull()) {
                throw new AiException("No content in answer: " + json);
            }
            return text(content);
        });
    }
}
