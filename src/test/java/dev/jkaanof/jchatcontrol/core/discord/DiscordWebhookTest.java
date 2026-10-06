package dev.jkaanof.jchatcontrol.core.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookTest {

    private HttpServer server;
    private String url;
    private final List<JsonObject> received = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final AtomicInteger rateLimitFirst = new AtomicInteger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/webhooks/1/abc", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            queries.add(ex.getRequestURI().getQuery());
            byte[] out;
            int code;
            if (rateLimitFirst.getAndDecrement() > 0) {
                code = 429;
                out = "{\"message\":\"You are being rate limited.\",\"retry_after\":0.2,\"global\":false}".getBytes();
            } else {
                received.add(JsonParser.parseString(body).getAsJsonObject());
                code = 200;
                out = "{\"id\":\"1\"}".getBytes();
            }
            ex.sendResponseHeaders(code, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/abc";
    }

    @AfterEach
    void stop() {
        server.stop(0);
        scheduler.shutdownNow();
    }

    private DiscordWebhook webhook() {
        DiscordWebhook.Settings s = new DiscordWebhook.Settings();
        s.batchMs = 100;
        return new DiscordWebhook(s, HttpClient.newHttpClient(), scheduler, Logger.getLogger("test"));
    }

    private static void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        for (int i = 0; i < 100 && !cond.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void batchesEmbedsAndBlocksMentions() throws Exception {
        DiscordWebhook w = webhook();
        for (int i = 0; i < 3; i++) {
            w.send(url, new DiscordEmbed().title("t" + i).color("#E74C3C").field("Oyuncu", "Steve", true)
                    .field("Mesaj", "@everyone bak", false).timestamp(Instant.EPOCH));
        }
        waitFor(() -> w.sentCount() == 3);
        assertEquals(1, received.size(), "three embeds in one request");
        JsonObject p = received.get(0);
        assertEquals(3, p.getAsJsonArray("embeds").size());
        assertEquals(0, p.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertEquals("JChatControl", p.get("username").getAsString());
        JsonObject e = p.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals(0xE74C3C, e.get("color").getAsInt());
        assertEquals("Steve", e.getAsJsonArray("fields").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("wait=true", queries.get(0));
    }

    @Test
    void retriesAfterRateLimit() throws Exception {
        rateLimitFirst.set(1);
        DiscordWebhook w = webhook();
        w.send(url, new DiscordEmbed().title("x"));
        waitFor(() -> w.sentCount() == 1);
        assertEquals(1, w.sentCount());
        assertEquals(2, queries.size(), "first answer 429, then retried");
    }

    @Test
    void limitsAndEscaping() {
        String longText = "a".repeat(5000);
        JsonObject e = new DiscordEmbed().title(longText).description(longText).field("f", longText, false).toJson();
        assertEquals(256, e.get("title").getAsString().length());
        assertEquals(4096, e.get("description").getAsString().length());
        assertEquals(1024, e.getAsJsonArray("fields").get(0).getAsJsonObject().get("value").getAsString().length());
        assertEquals("\\*\\*kalın\\*\\* \\|\\|x\\|\\|", DiscordEmbed.escape("**kalın** ||x||"));
        assertTrue(new DiscordEmbed().title("T").field("A", "B", true).toText().contains("**A:** B"));
        assertEquals(0x3498DB, DiscordEmbed.parseColor("#3498DB"));
        assertEquals(3447003, DiscordEmbed.parseColor("3447003"));
    }
}
