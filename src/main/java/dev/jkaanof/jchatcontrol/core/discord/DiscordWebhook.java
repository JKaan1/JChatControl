package dev.jkaanof.jchatcontrol.core.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Asynchronous Discord webhook sender.
 * <ul>
 *     <li>embeds are queued per webhook URL and sent in batches (up to 10 embeds per message)</li>
 *     <li>one request in flight at a time; HTTP 429 is respected (retry_after) and the batch is retried</li>
 *     <li>bounded queue: under a flood the oldest embeds are dropped instead of using memory</li>
 *     <li>mentions are disabled (allowed_mentions), so a chat message can never ping @everyone</li>
 * </ul>
 */
public final class DiscordWebhook {

    public static final class Settings {
        public String username = "JChatControl";
        public String avatarUrl = "";
        public boolean useEmbeds = true;
        public long batchMs = 2000;
        public int maxQueue = 200;
        public int maxRetries = 3;
    }

    private record Item(JsonObject embed, String text, int attempts) {
    }

    private final Settings settings;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final Logger logger;
    private final Map<String, List<Item>> queues = new LinkedHashMap<>();
    private boolean scheduled;
    private boolean sending;
    private long blockedUntil;
    private volatile int sent;
    private volatile int failed;

    public DiscordWebhook(Settings settings, HttpClient http, ScheduledExecutorService scheduler, Logger logger) {
        this.settings = settings;
        this.http = http;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    public int sentCount() {
        return sent;
    }

    public int failedCount() {
        return failed;
    }

    /** Queues an embed for a webhook URL. Thread-safe, never blocks. */
    public void send(String url, DiscordEmbed embed) {
        if (url == null || url.isBlank() || !url.startsWith("http")) {
            return;
        }
        synchronized (this) {
            List<Item> q = queues.computeIfAbsent(url.trim(), k -> new ArrayList<>());
            if (q.size() >= settings.maxQueue) {
                q.remove(0);
                failed++;
            }
            q.add(new Item(embed.toJson(), embed.toText(), 0));
            schedule(settings.batchMs);
        }
    }

    private void schedule(long delayMs) {
        if (scheduled || sending) {
            return;
        }
        scheduled = true;
        long wait = Math.max(delayMs, blockedUntil - System.currentTimeMillis());
        scheduler.schedule(this::flush, Math.max(0, wait), TimeUnit.MILLISECONDS);
    }

    private void flush() {
        String url;
        List<Item> batch;
        synchronized (this) {
            scheduled = false;
            if (sending) {
                return;
            }
            if (System.currentTimeMillis() < blockedUntil) {
                schedule(0);
                return;
            }
            var it = queues.entrySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            var entry = it.next();
            url = entry.getKey();
            List<Item> q = entry.getValue();
            int n = settings.useEmbeds ? Math.min(10, q.size()) : 1;
            batch = new ArrayList<>(q.subList(0, n));
            q.subList(0, n).clear();
            if (q.isEmpty()) {
                it.remove();
            }
            sending = true;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(withWait(url)))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "JChatControl (https://github.com/JKaan1/JChatControl)")
                .POST(HttpRequest.BodyPublishers.ofString(payload(batch).toString()))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, error) -> {
            synchronized (this) {
                sending = false;
                if (error != null) {
                    logger.warning("[Discord] webhook failed: " + error.getMessage());
                    requeue(url, batch);
                } else if (resp.statusCode() == 429) {
                    long retry = retryAfterMs(resp);
                    blockedUntil = System.currentTimeMillis() + retry;
                    requeue(url, batch);
                } else if (resp.statusCode() / 100 != 2) {
                    failed += batch.size();
                    logger.warning("[Discord] webhook answered HTTP " + resp.statusCode() + ": "
                            + abbreviate(resp.body()) + " (check discord.webhook-url)");
                } else {
                    sent += batch.size();
                }
                if (!queues.isEmpty()) {
                    // stay well below Discord's 5 requests / 2 seconds per webhook
                    schedule(500);
                }
            }
        });
    }

    private void requeue(String url, List<Item> batch) {
        List<Item> retry = new ArrayList<>();
        for (Item i : batch) {
            if (i.attempts() + 1 < settings.maxRetries) {
                retry.add(new Item(i.embed(), i.text(), i.attempts() + 1));
            } else {
                failed++;
            }
        }
        if (!retry.isEmpty()) {
            queues.computeIfAbsent(url, k -> new ArrayList<>()).addAll(0, retry);
        }
    }

    private JsonObject payload(List<Item> batch) {
        JsonObject p = new JsonObject();
        if (!settings.username.isBlank()) {
            p.addProperty("username", settings.username);
        }
        if (settings.avatarUrl.startsWith("http")) {
            p.addProperty("avatar_url", settings.avatarUrl);
        }
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        p.add("allowed_mentions", mentions);
        if (settings.useEmbeds) {
            JsonArray embeds = new JsonArray();
            batch.forEach(i -> embeds.add(i.embed()));
            p.add("embeds", embeds);
        } else {
            p.addProperty("content", batch.get(0).text());
        }
        return p;
    }

    private static String withWait(String url) {
        // ?wait=true makes Discord report errors (bad webhook, invalid embed) instead of silently dropping
        return url.contains("?") ? url + "&wait=true" : url + "?wait=true";
    }

    static long retryAfterMs(HttpResponse<String> resp) {
        try {
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (o.has("retry_after")) {
                return (long) Math.ceil(o.get("retry_after").getAsDouble() * 1000) + 100;
            }
        } catch (RuntimeException ignored) {
            // fall through to header
        }
        return resp.headers().firstValue("Retry-After").map(v -> {
            try {
                return (long) (Double.parseDouble(v) * 1000) + 100;
            } catch (NumberFormatException e) {
                return 2000L;
            }
        }).orElse(2000L);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** Pending embeds (for /jcc discord status). */
    public synchronized int queued() {
        int n = 0;
        for (List<Item> q : queues.values()) {
            n += q.size();
        }
        return n;
    }
}
