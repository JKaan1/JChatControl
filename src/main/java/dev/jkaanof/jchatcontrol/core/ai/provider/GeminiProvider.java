package dev.jkaanof.jchatcontrol.core.ai.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.jkaanof.jchatcontrol.core.ai.AiException;
import dev.jkaanof.jchatcontrol.util.JsonPath;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Google Gemini generateContent API. */
public final class GeminiProvider extends ChatProvider {

    private final String baseUrl;

    public GeminiProvider(String name, Section cfg, ProviderContext ctx) {
        super(name, cfg, ctx);
        this.baseUrl = trimSlash(cfg.getString("base-url", "https://generativelanguage.googleapis.com/v1beta"));
    }

    @Override
    public String type() {
        return "gemini";
    }

    @Override
    protected CompletableFuture<String> request(String system, String user, int tokens, boolean jsonMode) {
        JsonObject body = new JsonObject();
        if (system != null) {
            JsonObject si = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject p = new JsonObject();
            p.addProperty("text", system);
            parts.add(p);
            si.add("parts", parts);
            body.add("systemInstruction", si);
        }
        JsonArray contents = new JsonArray();
        JsonObject c = new JsonObject();
        c.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("text", user);
        parts.add(p);
        c.add("parts", parts);
        contents.add(c);
        body.add("contents", contents);
        JsonObject gen = new JsonObject();
        gen.addProperty("temperature", temperature);
        gen.addProperty("maxOutputTokens", tokens);
        if (jsonMode) {
            gen.addProperty("responseMimeType", "application/json");
        }
        int thinkingBudget = cfg.getInt("thinking-budget", -1);
        if (thinkingBudget >= 0) {
            JsonObject tc = new JsonObject();
            tc.addProperty("thinkingBudget", thinkingBudget);
            gen.add("thinkingConfig", tc);
        }
        body.add("generationConfig", gen);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-goog-api-key", apiKey);
        String url = baseUrl + "/models/" + model + ":generateContent";
        return postJson(url, body.toString(), headers).thenApply(json -> {
            JsonElement partsEl = JsonPath.get(json, "candidates[0].content.parts");
            if (partsEl == null || !partsEl.isJsonArray()) {
                throw new AiException("No content in answer: " + json);
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : partsEl.getAsJsonArray()) {
                JsonObject po = part.getAsJsonObject();
                if (po.has("text") && !(po.has("thought") && po.get("thought").getAsBoolean())) {
                    sb.append(po.get("text").getAsString());
                }
            }
            return sb.toString();
        });
    }
}
