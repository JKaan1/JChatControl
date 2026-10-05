package dev.jkaanof.jchatcontrol.core.ai.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.jkaanof.jchatcontrol.core.ai.AiResult;
import dev.jkaanof.jchatcontrol.util.JsonPath;
import dev.jkaanof.jchatcontrol.util.Section;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Google Jigsaw Perspective API (toxicity decision model). One request per message. */
public final class PerspectiveProvider extends AbstractProvider {

    private final String url;
    private final List<String> attributes;
    private final List<String> languages;
    private final ScoreMapper mapper;

    public PerspectiveProvider(String name, Section cfg, ProviderContext ctx) {
        super(name, cfg, ctx);
        this.url = trimSlash(cfg.getString("base-url", "https://commentanalyzer.googleapis.com/v1alpha1"))
                + "/comments:analyze?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        List<String> attrs = cfg.getStringList("attributes");
        this.attributes = attrs.isEmpty() ? List.of("TOXICITY", "SEVERE_TOXICITY", "INSULT", "PROFANITY",
                "IDENTITY_ATTACK", "THREAT") : attrs;
        this.languages = cfg.getStringList("languages");
        this.mapper = new ScoreMapper(cfg, ctx.defaultCategory(), 0.7);
    }

    @Override
    public String type() {
        return "perspective";
    }

    @Override
    public int maxBatch() {
        return 1;
    }

    @Override
    public CompletableFuture<List<AiResult>> classify(List<String> messages) {
        return track(eachSingle(messages, this::one));
    }

    private CompletableFuture<AiResult> one(String message) {
        JsonObject body = new JsonObject();
        JsonObject comment = new JsonObject();
        comment.addProperty("text", message);
        body.add("comment", comment);
        JsonObject req = new JsonObject();
        for (String a : attributes) {
            req.add(a, new JsonObject());
        }
        body.add("requestedAttributes", req);
        if (!languages.isEmpty()) {
            JsonArray langs = new JsonArray();
            languages.forEach(langs::add);
            body.add("languages", langs);
        }
        body.addProperty("doNotStore", true);
        return postJson(url, body.toString(), Map.of()).thenApply(json -> {
            Map<String, Double> scores = new LinkedHashMap<>();
            JsonObject attrs = JsonPath.obj(JsonPath.get(json, "attributeScores"));
            if (attrs != null) {
                for (Map.Entry<String, JsonElement> e : attrs.entrySet()) {
                    JsonElement v = JsonPath.get(e.getValue(), "summaryScore.value");
                    if (v != null && v.isJsonPrimitive()) {
                        scores.put(e.getKey(), v.getAsDouble());
                    }
                }
            }
            return mapper.map(scores, name);
        });
    }
}
