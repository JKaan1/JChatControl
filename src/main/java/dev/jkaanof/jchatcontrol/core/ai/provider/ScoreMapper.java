package dev.jkaanof.jchatcontrol.core.ai.provider;

import dev.jkaanof.jchatcontrol.core.ai.AiResult;
import dev.jkaanof.jchatcontrol.util.Section;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts "label -> score" answers of decision models into an {@link AiResult}.
 * Each label is mapped to a plugin category (or "ignore") and compared with its threshold.
 */
final class ScoreMapper {

    private final Map<String, String> categoryMap = new HashMap<>();
    private final Map<String, Double> thresholds = new HashMap<>();
    private final double defaultThreshold;
    private final String defaultCategory;

    ScoreMapper(Section cfg, String defaultCategory, double defaultThreshold) {
        cfg.getStringMap("category-map").forEach((k, v) -> categoryMap.put(k.toLowerCase(Locale.ROOT), v));
        Section th = cfg.getSection("thresholds");
        for (String k : th.keys()) {
            thresholds.put(k.toLowerCase(Locale.ROOT), th.getDouble(k, defaultThreshold));
        }
        this.defaultThreshold = cfg.getDouble("threshold", defaultThreshold);
        this.defaultCategory = cfg.getString("default-category", defaultCategory);
    }

    AiResult map(Map<String, Double> scores, String provider) {
        List<String> cats = new ArrayList<>();
        double max = 0;
        boolean flagged = false;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            String label = e.getKey().toLowerCase(Locale.ROOT);
            String cat = categoryMap.getOrDefault(label, defaultCategory);
            if ("ignore".equalsIgnoreCase(cat)) {
                continue;
            }
            double score = e.getValue();
            max = Math.max(max, score);
            if (score >= thresholds.getOrDefault(label, defaultThreshold)) {
                flagged = true;
                if (!cats.contains(cat)) {
                    cats.add(cat);
                }
            }
        }
        return new AiResult(flagged, flagged ? cats : List.of(), List.of(), max, provider);
    }
}
