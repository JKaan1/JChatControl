package dev.jkaanof.jchatcontrol.core.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a model's text answer into {@link AiResult}s.
 * <ul>
 *     <li>JSON - our own batch format (generative LLMs: Llama, Qwen, Gemma, GPT, Claude, Gemini ...)</li>
 *     <li>GUARD - decision models answering "safe" / "unsafe\nS1,S10" (Llama Guard, Granite Guardian ...)</li>
 *     <li>YES_NO - decision models answering "Yes" (violation) / "No" (ShieldGemma ...)</li>
 *     <li>LABEL - any classifier answering with a label; flagged if the answer contains one of the configured labels</li>
 * </ul>
 */
public final class ResponseParser {

    public enum Mode { JSON, GUARD, YES_NO, LABEL }

    private static final Pattern GUARD_CODE = Pattern.compile("\\b(S\\d{1,2}|O\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    private final Mode mode;
    private final Set<String> knownCategories;
    private final String defaultCategory;
    private final Map<String, String> categoryMap;
    private final List<String> flagLabels;

    public ResponseParser(Mode mode, Set<String> knownCategories, String defaultCategory,
                          Map<String, String> categoryMap, List<String> flagLabels) {
        this.mode = mode;
        this.knownCategories = knownCategories;
        this.defaultCategory = defaultCategory;
        this.categoryMap = lowerKeys(categoryMap);
        this.flagLabels = flagLabels.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    private static Map<String, String> lowerKeys(Map<String, String> m) {
        java.util.HashMap<String, String> out = new java.util.HashMap<>();
        m.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
        return out;
    }

    public Mode mode() {
        return mode;
    }

    public boolean supportsBatch() {
        return mode == Mode.JSON;
    }

    /** Parses an answer for {@code count} messages. */
    public List<AiResult> parse(String text, int count, String provider) {
        if (mode == Mode.JSON) {
            return parseJson(text, count, provider);
        }
        if (count != 1) {
            throw new AiException("Mode " + mode + " supports one message per request");
        }
        return Collections.singletonList(parseSingle(text, provider));
    }

    private AiResult parseSingle(String text, String provider) {
        String t = text == null ? "" : text.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        switch (mode) {
            case GUARD -> {
                if (lower.startsWith("safe") || lower.isEmpty()) {
                    return new AiResult(false, List.of(), List.of(), 0.0, provider);
                }
                if (!lower.startsWith("unsafe")) {
                    throw new AiException("Unexpected guard answer: " + abbreviate(t));
                }
                List<String> cats = new ArrayList<>();
                Matcher m = GUARD_CODE.matcher(t);
                while (m.find()) {
                    String mapped = categoryMap.get(m.group(1).toLowerCase(Locale.ROOT));
                    if (mapped != null && !mapped.equalsIgnoreCase("ignore") && !cats.contains(mapped)) {
                        cats.add(mapped);
                    } else if (mapped == null && !cats.contains(defaultCategory)) {
                        cats.add(defaultCategory);
                    }
                }
                if (cats.isEmpty()) {
                    // every reported code is mapped to "ignore"
                    if (GUARD_CODE.matcher(t).find()) {
                        return new AiResult(false, List.of(), List.of(), 0.0, provider);
                    }
                    cats.add(defaultCategory);
                }
                return new AiResult(true, cats, List.of(), 1.0, provider);
            }
            case YES_NO -> {
                boolean yes = lower.startsWith("yes") || lower.startsWith("evet") || lower.startsWith("true");
                return new AiResult(yes, yes ? List.of(defaultCategory) : List.of(), List.of(), yes ? 1.0 : 0.0, provider);
            }
            case LABEL -> {
                for (String label : flagLabels) {
                    if (lower.contains(label)) {
                        String mapped = categoryMap.getOrDefault(label, defaultCategory);
                        return new AiResult(true, List.of(mapped), List.of(), 1.0, provider);
                    }
                }
                return new AiResult(false, List.of(), List.of(), 0.0, provider);
            }
            default -> throw new IllegalStateException();
        }
    }

    private List<AiResult> parseJson(String text, int count, String provider) {
        JsonElement root = extractJson(text);
        JsonArray arr = null;
        if (root.isJsonArray()) {
            arr = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            for (String key : new String[]{"r", "results", "result", "messages", "data"}) {
                if (o.has(key) && o.get(key).isJsonArray()) {
                    arr = o.getAsJsonArray(key);
                    break;
                }
            }
            if (arr == null && (o.has("f") || o.has("flagged"))) {
                arr = new JsonArray();
                arr.add(o);
            }
        }
        if (arr == null) {
            throw new AiException("No result array in answer: " + abbreviate(text));
        }
        AiResult[] out = new AiResult[count];
        int position = 0;
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) {
                position++;
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            int id = intOf(o, position, "i", "id", "index");
            position++;
            if (id < 0 || id >= count || out[id] != null) {
                continue;
            }
            boolean flagged = boolOf(o, "f", "flagged", "violation", "unsafe");
            double score = doubleOf(o, flagged ? 1.0 : 0.0, "s", "score", "confidence");
            List<String> cats = new ArrayList<>();
            for (String c : stringsOf(o, "c", "categories", "category")) {
                String norm = mapCategory(c);
                if (norm != null && !cats.contains(norm)) {
                    cats.add(norm);
                }
            }
            if (flagged && cats.isEmpty()) {
                cats.add(defaultCategory);
            }
            List<String> words = stringsOf(o, "w", "words", "offending");
            out[id] = new AiResult(flagged, flagged ? cats : List.of(), flagged ? words : List.of(), score, provider);
        }
        return Arrays.asList(out);
    }

    private String mapCategory(String c) {
        if (c == null) {
            return null;
        }
        String lower = c.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return null;
        }
        String mapped = categoryMap.get(lower);
        if (mapped != null) {
            return mapped.equalsIgnoreCase("ignore") ? null : mapped;
        }
        if (knownCategories.contains(lower)) {
            return lower;
        }
        return defaultCategory;
    }

    /** Finds the JSON part of an answer (models sometimes wrap it in ```json fences or prose). */
    static JsonElement extractJson(String text) {
        if (text == null) {
            throw new AiException("Empty answer");
        }
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');
        int start;
        int end;
        if (objStart >= 0 && (arrStart < 0 || objStart < arrStart)) {
            start = objStart;
            end = text.lastIndexOf('}');
        } else {
            start = arrStart;
            end = text.lastIndexOf(']');
        }
        if (start < 0 || end <= start) {
            throw new AiException("No JSON in answer: " + abbreviate(text));
        }
        try {
            return JsonParser.parseString(text.substring(start, end + 1));
        } catch (RuntimeException e) {
            throw new AiException("Invalid JSON in answer: " + abbreviate(text), e);
        }
    }

    private static int intOf(JsonObject o, int def, String... keys) {
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e != null && e.isJsonPrimitive()) {
                try {
                    return (int) Double.parseDouble(e.getAsString());
                } catch (NumberFormatException ignored) {
                    // try next
                }
            }
        }
        return def;
    }

    private static boolean boolOf(JsonObject o, String... keys) {
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e != null && e.isJsonPrimitive()) {
                String s = e.getAsString().trim().toLowerCase(Locale.ROOT);
                return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("unsafe");
            }
        }
        return false;
    }

    private static double doubleOf(JsonObject o, double def, String... keys) {
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e != null && e.isJsonPrimitive()) {
                try {
                    double d = Double.parseDouble(e.getAsString());
                    if (d > 1.0 && d <= 100.0) {
                        d /= 100.0; // some models answer in percent
                    }
                    return Math.max(0.0, Math.min(1.0, d));
                } catch (NumberFormatException ignored) {
                    // try next
                }
            }
        }
        return def;
    }

    private static List<String> stringsOf(JsonObject o, String... keys) {
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e == null || e.isJsonNull()) {
                continue;
            }
            List<String> out = new ArrayList<>();
            if (e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    if (x.isJsonPrimitive() && !x.getAsString().isBlank()) {
                        out.add(x.getAsString().trim());
                    }
                }
            } else if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                for (String part : e.getAsString().split(",")) {
                    if (!part.isBlank()) {
                        out.add(part.trim());
                    }
                }
            }
            return out;
        }
        return new ArrayList<>();
    }

    static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        s = s.replace('\n', ' ');
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
