package dev.jkaanof.jchatcontrol.core.ai.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.jkaanof.jchatcontrol.core.ai.AiException;
import dev.jkaanof.jchatcontrol.core.ai.AiResult;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI moderation endpoint (free with an OpenAI key, natively supports batches).
 * Decision model: returns per-category scores instead of generated text.
 */
public final class OpenAiModerationProvider extends AbstractProvider {

    private final String url;
    private final String model;
    private final ScoreMapper mapper;
    private final int maxBatch;

    public OpenAiModerationProvider(String name, Section cfg, ProviderContext ctx) {
        super(name, cfg, ctx);
        this.url = trimSlash(cfg.getString("base-url", "https://api.openai.com/v1")) + "/moderations";
        this.model = cfg.getString("model", "omni-moderation-latest");
        this.mapper = new ScoreMapper(cfg, ctx.defaultCategory(), 0.5);
        this.maxBatch = Math.max(1, cfg.getInt("max-batch", 20));
    }

    @Override
    public String type() {
        return "openai-moderation";
    }

    @Override
    public int maxBatch() {
        return maxBatch;
    }

    @Override
    public CompletableFuture<List<AiResult>> classify(List<String> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray input = new JsonArray();
        messages.forEach(input::add);
        body.add("input", input);
        return track(postJson(url, body.toString(), Map.of("Authorization", "Bearer " + apiKey)).thenApply(json -> {
            JsonArray results = json.getAsJsonObject().getAsJsonArray("results");
            if (results == null) {
                throw new AiException("No results in moderation answer");
            }
            List<AiResult> out = new ArrayList<>(messages.size());
            for (int i = 0; i < messages.size(); i++) {
                if (i >= results.size()) {
                    out.add(null);
                    continue;
                }
                JsonObject r = results.get(i).getAsJsonObject();
                JsonObject scores = r.getAsJsonObject("category_scores");
                Map<String, Double> map = new LinkedHashMap<>();
                if (scores != null) {
                    for (Map.Entry<String, JsonElement> e : scores.entrySet()) {
                        map.put(e.getKey(), e.getValue().getAsDouble());
                    }
                }
                out.add(mapper.map(map, name));
            }
            return out;
        }));
    }
}
