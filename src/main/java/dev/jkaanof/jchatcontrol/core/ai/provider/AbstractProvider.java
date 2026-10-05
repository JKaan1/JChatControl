package dev.jkaanof.jchatcontrol.core.ai.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.jkaanof.jchatcontrol.core.ai.AiException;
import dev.jkaanof.jchatcontrol.core.ai.AiProvider;
import dev.jkaanof.jchatcontrol.core.ai.AiResult;
import dev.jkaanof.jchatcontrol.util.Section;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** HTTP helpers, API key resolution and a circuit breaker shared by all providers. */
public abstract class AbstractProvider implements AiProvider {

    protected final String name;
    protected final Section cfg;
    protected final ProviderContext ctx;
    protected final Duration timeout;
    protected final Map<String, String> extraHeaders;
    protected final String apiKey;

    private final int breakerFailures;
    private final long breakerCooldownMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntil;
    private volatile String lastError = "";

    protected AbstractProvider(String name, Section cfg, ProviderContext ctx) {
        this.name = name;
        this.cfg = cfg;
        this.ctx = ctx;
        this.timeout = Duration.ofMillis(Math.max(500, cfg.getLong("timeout-ms", 4000)));
        this.extraHeaders = cfg.getStringMap("headers");
        this.apiKey = resolveKey(cfg.getString("api-key", ""));
        this.breakerFailures = Math.max(1, cfg.getInt("circuit-breaker.failures", 3));
        this.breakerCooldownMs = Math.max(1, cfg.getLong("circuit-breaker.cooldown-seconds", 30)) * 1000L;
    }

    /** "env:OPENAI_API_KEY" reads the key from an environment variable instead of the config file. */
    static String resolveKey(String raw) {
        if (raw == null) {
            return "";
        }
        String k = raw.trim();
        if (k.regionMatches(true, 0, "env:", 0, 4)) {
            String v = System.getenv(k.substring(4).trim());
            return v == null ? "" : v.trim();
        }
        return k;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean available() {
        return System.currentTimeMillis() >= openUntil;
    }

    @Override
    public String status() {
        if (!available()) {
            long s = (openUntil - System.currentTimeMillis()) / 1000;
            return "paused " + s + "s (" + lastError + ")";
        }
        return consecutiveFailures.get() == 0 ? "ok" : "failing (" + lastError + ")";
    }

    protected void success() {
        consecutiveFailures.set(0);
    }

    protected void failure(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        lastError = cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
        if (consecutiveFailures.incrementAndGet() >= breakerFailures) {
            openUntil = System.currentTimeMillis() + breakerCooldownMs;
            consecutiveFailures.set(0);
            ctx.logger().warning("[AI] Provider '" + name + "' paused for " + breakerCooldownMs / 1000
                    + "s after repeated failures: " + lastError);
        }
    }

    /** Wraps a classify call with circuit breaker bookkeeping. */
    protected CompletableFuture<List<AiResult>> track(CompletableFuture<List<AiResult>> f) {
        return f.whenComplete((r, t) -> {
            if (t != null) {
                failure(t);
            } else {
                success();
            }
        });
    }

    /**
     * Runs one request per message (for providers / parsers without batch support) and merges the answers.
     */
    protected CompletableFuture<List<AiResult>> eachSingle(List<String> messages,
                                                           java.util.function.Function<String, CompletableFuture<AiResult>> one) {
        List<CompletableFuture<AiResult>> futures = new ArrayList<>(messages.size());
        for (String m : messages) {
            futures.add(one.apply(m));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(v -> {
            AiResult[] out = new AiResult[futures.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = futures.get(i).join();
            }
            return Arrays.asList(out);
        });
    }

    protected CompletableFuture<JsonElement> postJson(String url, String body, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                // plain http (local servers): skip the h2c upgrade dance, some local servers dislike it
                .version(url.startsWith("http://") ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "JChatControl")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        extraHeaders.forEach(b::header);
        if (ctx.debug()) {
            ctx.logger().info("[AI:" + name + "] -> " + url + " " + body);
        }
        long start = System.nanoTime();
        return ctx.http().sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            if (ctx.debug()) {
                ctx.logger().info("[AI:" + name + "] <- " + resp.statusCode() + " in "
                        + (System.nanoTime() - start) / 1_000_000 + "ms " + resp.body());
            }
            if (resp.statusCode() / 100 != 2) {
                String b2 = resp.body() == null ? "" : resp.body().replace('\n', ' ');
                throw new AiException("HTTP " + resp.statusCode() + ": " + (b2.length() > 300 ? b2.substring(0, 300) : b2));
            }
            try {
                return JsonParser.parseString(resp.body());
            } catch (RuntimeException e) {
                throw new AiException("Invalid JSON response", e);
            }
        });
    }

    protected static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
