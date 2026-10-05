package dev.jkaanof.jchatcontrol.core.ai.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.jkaanof.jchatcontrol.core.ai.AiResult;
import dev.jkaanof.jchatcontrol.core.ai.ResponseParser;
import dev.jkaanof.jchatcontrol.util.JsonPath;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Any HTTP decision / classifier service (self hosted Detoxify, a fine-tuned BERT, a HuggingFace
 * inference endpoint, your own API ...). Request body and answer paths are fully configurable.
 */
public final class CustomHttpProvider extends AbstractProvider {

    private final String url;
    private final String bodyTemplate;
    private final String scoresPath;
    private final String flaggedPath;
    private final List<String> flaggedValues;
    private final String scorePath;
    private final String categoryPath;
    private final String textPath;
    private final ResponseParser textParser;
    private final ScoreMapper mapper;
    private final double threshold;
    private final String defaultCategory;
    private final Map<String, String> categoryMap = new HashMap<>();

    public CustomHttpProvider(String name, Section cfg, ProviderContext ctx) {
        super(name, cfg, ctx);
        this.url = cfg.getString("url", "http://localhost:5000/classify");
        this.bodyTemplate = cfg.getString("body", "{\"text\": {message}}");
        this.scoresPath = cfg.getString("response.scores-path", "");
        this.flaggedPath = cfg.getString("response.flagged-path", "");
        this.flaggedValues = cfg.getStringList("response.flagged-values").stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.scorePath = cfg.getString("response.score-path", "");
        this.categoryPath = cfg.getString("response.category-path", "");
        this.textPath = cfg.getString("response.text-path", "");
        this.defaultCategory = cfg.getString("default-category", ctx.defaultCategory());
        cfg.getStringMap("category-map").forEach((k, v) -> categoryMap.put(k.toLowerCase(Locale.ROOT), v));
        this.textParser = textPath.isEmpty() ? null : new ResponseParser(
                ChatProvider.parseMode(cfg.getString("parser", "guard")), ctx.categories(), defaultCategory,
                cfg.getStringMap("category-map"), cfg.getStringList("flag-labels"));
        this.threshold = cfg.getDouble("threshold", 0.5);
        this.mapper = new ScoreMapper(cfg, ctx.defaultCategory(), threshold);
    }

    @Override
    public String type() {
        return "custom";
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
        String body = bodyTemplate.replace("{message}", new JsonPrimitive(message).toString());
        Map<String, String> headers = apiKey.isEmpty() ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
        return postJson(url, body, headers).thenApply(this::read);
    }

    private AiResult read(JsonElement json) {
        if (textParser != null) {
            return textParser.parse(JsonPath.getString(json, textPath), 1, name).get(0);
        }
        if (!scoresPath.isEmpty()) {
            Map<String, Double> scores = new LinkedHashMap<>();
            JsonElement el = JsonPath.get(json, scoresPath);
            if (el != null && el.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        scores.put(e.getKey(), e.getValue().getAsDouble());
                    }
                }
            } else if (el != null && el.isJsonArray()) {
                // HuggingFace style: [{"label":"toxic","score":0.98}, ...]
                for (JsonElement e : el.getAsJsonArray()) {
                    JsonObject o = JsonPath.obj(e.isJsonArray() && !e.getAsJsonArray().isEmpty() ? e.getAsJsonArray().get(0) : e);
                    if (o != null && o.has("label") && o.has("score")) {
                        scores.put(o.get("label").getAsString(), o.get("score").getAsDouble());
                    }
                }
            }
            return mapper.map(scores, name);
        }
        double score = 0;
        boolean flagged = false;
        if (!scorePath.isEmpty()) {
            String s = JsonPath.getString(json, scorePath);
            try {
                score = s == null ? 0 : Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                score = 0;
            }
            flagged = score >= threshold;
        }
        if (!flaggedPath.isEmpty()) {
            String v = JsonPath.getString(json, flaggedPath);
            String lower = v == null ? "" : v.toLowerCase(Locale.ROOT);
            flagged = flaggedValues.isEmpty()
                    ? lower.equals("true") || lower.equals("1") || lower.equals("yes") || lower.equals("unsafe")
                    : flaggedValues.contains(lower);
            if (scorePath.isEmpty()) {
                score = flagged ? 1.0 : 0.0;
            }
        }
        String category = defaultCategory;
        if (!categoryPath.isEmpty()) {
            String c = JsonPath.getString(json, categoryPath);
            if (c != null) {
                category = categoryMap.getOrDefault(c.toLowerCase(Locale.ROOT),
                        ctx.categories().contains(c.toLowerCase(Locale.ROOT)) ? c.toLowerCase(Locale.ROOT) : defaultCategory);
            }
        }
        return new AiResult(flagged, flagged ? List.of(category) : List.of(), List.of(), score, name);
    }
}
