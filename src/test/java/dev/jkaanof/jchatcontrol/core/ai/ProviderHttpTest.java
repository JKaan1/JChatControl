package dev.jkaanof.jchatcontrol.core.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.jkaanof.jchatcontrol.core.CategorySettings;
import dev.jkaanof.jchatcontrol.core.Action;
import dev.jkaanof.jchatcontrol.core.ai.provider.ProviderContext;
import dev.jkaanof.jchatcontrol.core.ai.provider.ProviderFactory;
import dev.jkaanof.jchatcontrol.util.Section;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs every provider against a fake local HTTP server and checks request + response handling. */
class ProviderHttpTest {

    private HttpServer server;
    private String base;
    private final Map<String, JsonObject> lastRequest = new ConcurrentHashMap<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private ProviderContext ctx;

    private static final String BATCH = "{\"r\":[{\"i\":0,\"f\":false,\"c\":[],\"w\":[],\"s\":0.01},"
            + "{\"i\":1,\"f\":true,\"c\":[\"insult\"],\"w\":[\"salak\"],\"s\":0.93}]}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        route("/api/chat", "{\"message\":{\"role\":\"assistant\",\"content\":" + q(BATCH) + "},\"done\":true}");
        route("/v1/chat/completions", "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + q(BATCH) + "}}]}");
        route("/v1/messages", "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"},{\"type\":\"text\",\"text\":"
                + q(BATCH) + "}],\"stop_reason\":\"end_turn\"}");
        route("/v1beta/models/gem:generateContent", "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + q(BATCH) + "}]}}]}");
        route("/v1/moderations", "{\"results\":[{\"flagged\":false,\"category_scores\":{\"hate\":0.01,\"harassment\":0.02}},"
                + "{\"flagged\":true,\"category_scores\":{\"hate\":0.97,\"harassment\":0.4}}]}");
        route("/guard/api/chat", "{\"message\":{\"content\":\"unsafe\\nS10\"}}");
        route("/classify", "{\"result\":{\"toxic\":true,\"score\":0.88,\"label\":\"insult\"}}");
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        PromptBuilder prompt = new PromptBuilder("", List.of(
                new CategorySettings("insult", true, "Hakaret", Action.BLOCK, 1, "insults"),
                new CategorySettings("racism", true, "Irkçılık", Action.BLOCK, 1, "racism")), List.of(), "");
        ctx = new ProviderContext(HttpClient.newHttpClient(), prompt, Set.of("insult", "racism", "profanity"),
                "profanity", Logger.getLogger("test"), false);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String q(String s) {
        return new com.google.gson.JsonPrimitive(s).toString();
    }

    private void route(String path, String answer) {
        server.createContext(path, ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequest.put(path, JsonParser.parseString(body).getAsJsonObject());
            ex.getRequestHeaders().forEach((k, v) -> lastHeaders.put(path + "|" + k.toLowerCase(), v.get(0)));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
    }

    private Section cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new Section(m);
    }

    private List<AiResult> run(Section cfg, List<String> messages) throws Exception {
        AiProvider p = ProviderFactory.create("p", cfg, ctx);
        return p.classify(messages).get(5, TimeUnit.SECONDS);
    }

    private void assertBatch(List<AiResult> r) {
        assertEquals(2, r.size());
        assertFalse(r.get(0).flagged());
        assertTrue(r.get(1).flagged());
        assertEquals(List.of("insult"), r.get(1).categories());
        assertEquals(List.of("salak"), r.get(1).words());
    }

    @Test
    void ollama() throws Exception {
        assertBatch(run(cfg("type", "ollama", "base-url", base, "model", "qwen"), List.of("selam", "salak")));
        JsonObject req = lastRequest.get("/api/chat");
        assertEquals("json", req.get("format").getAsString());
        assertEquals("system", req.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(req.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().contains("\"m\":\"salak\""));
    }

    @Test
    void openAiCompatible() throws Exception {
        assertBatch(run(cfg("type", "openai", "base-url", base + "/v1", "model", "m", "api-key", "k1"), List.of("a", "b")));
        assertEquals("Bearer k1", lastHeaders.get("/v1/chat/completions|authorization"));
        assertEquals("json_object", lastRequest.get("/v1/chat/completions").getAsJsonObject("response_format").get("type").getAsString());
    }

    @Test
    void anthropic() throws Exception {
        assertBatch(run(cfg("type", "anthropic", "base-url", base + "/v1", "model", "claude-opus-5-5", "api-key", "k2"),
                List.of("a", "b")));
        JsonObject req = lastRequest.get("/v1/messages");
        assertEquals("k2", lastHeaders.get("/v1/messages|x-api-key"));
        assertEquals("2023-06-01", lastHeaders.get("/v1/messages|anthropic-version"));
        assertEquals("low", req.getAsJsonObject("output_config").get("effort").getAsString());
        assertTrue(req.getAsJsonArray("system").get(0).getAsJsonObject().has("cache_control"));
    }

    @Test
    void gemini() throws Exception {
        assertBatch(run(cfg("type", "gemini", "base-url", base + "/v1beta", "model", "gem", "api-key", "k3"), List.of("a", "b")));
        assertEquals("k3", lastHeaders.get("/v1beta/models/gem:generateContent|x-goog-api-key"));
    }

    @Test
    void openAiModeration() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("hate", "racism");
        map.put("harassment", "insult");
        List<AiResult> r = run(cfg("type", "openai-moderation", "base-url", base + "/v1", "category-map", map), List.of("a", "b"));
        assertFalse(r.get(0).flagged());
        assertTrue(r.get(1).flagged());
        assertEquals(List.of("racism"), r.get(1).categories());
        assertEquals(2, lastRequest.get("/v1/moderations").getAsJsonArray("input").size());
    }

    @Test
    void guardDecisionModel() throws Exception {
        Map<String, Object> map = Map.of("S10", "racism");
        List<AiResult> r = run(cfg("type", "ollama", "base-url", base + "/guard", "model", "llama-guard3",
                "parser", "guard", "category-map", map), List.of("x"));
        assertTrue(r.get(0).flagged());
        assertEquals(List.of("racism"), r.get(0).categories());
        // guard models get the raw message, no system prompt
        assertEquals(1, lastRequest.get("/guard/api/chat").getAsJsonArray("messages").size());
    }

    @Test
    void customClassifier() throws Exception {
        Map<String, Object> response = Map.of("flagged-path", "result.toxic", "score-path", "result.score",
                "category-path", "result.label");
        List<AiResult> r = run(cfg("type", "custom", "url", base + "/classify", "body", "{\"text\": {message}}",
                "response", response), List.of("he said \"hi\""));
        assertTrue(r.get(0).flagged());
        assertEquals(List.of("insult"), r.get(0).categories());
        assertEquals("he said \"hi\"", lastRequest.get("/classify").get("text").getAsString());
    }
}
