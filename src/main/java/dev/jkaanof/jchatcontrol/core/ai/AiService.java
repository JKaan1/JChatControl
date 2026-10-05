package dev.jkaanof.jchatcontrol.core.ai;

import dev.jkaanof.jchatcontrol.core.Stats;
import dev.jkaanof.jchatcontrol.core.Verdict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends messages to the AI providers while keeping API usage as low as possible:
 * <ul>
 *     <li><b>de-duplication</b>: identical (normalized) messages in flight share one request</li>
 *     <li><b>batching</b>: messages arriving within a short window go out in one request</li>
 *     <li><b>rate limits</b>: global requests/minute and per-player checks/minute</li>
 *     <li><b>provider chain</b>: FALLBACK (next provider on error) or ESCALATE (cheap decision model first,
 *     ask the next provider only when the first one is unsure)</li>
 * </ul>
 */
public final class AiService {

    public enum Strategy { FALLBACK, ESCALATE }

    public static final class Settings {
        public boolean batchEnabled = true;
        public long batchWindowMs = 150;
        public int batchMax = 10;
        public int requestsPerMinute = 60;
        public int playerChecksPerMinute = 6;
        public Strategy strategy = Strategy.FALLBACK;
        public double uncertainMin = 0.35;
        public double uncertainMax = 0.75;
        public double minConfidence = 0.6;
        public int maxMessageLength = 256;
        public boolean failOpen = true;
        public long hardTimeoutMs = 15000;
    }

    private record Pending(String key, String text, CompletableFuture<Verdict> future) {
    }

    private final Settings settings;
    private final List<AiProvider> providers;
    private final ScheduledExecutorService scheduler;
    private final Logger logger;
    private final Stats stats;
    private final BiConsumer<String, Verdict> onResult;

    private final Map<String, CompletableFuture<Verdict>> inFlight = new ConcurrentHashMap<>();
    private final Queue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();

    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger windowRequests = new AtomicInteger();
    private final Map<UUID, long[]> playerWindows = new ConcurrentHashMap<>();

    /**
     * @param onResult called once for every fresh AI verdict (normalized key, verdict) - used for caching and learning
     */
    public AiService(Settings settings, List<AiProvider> providers, ScheduledExecutorService scheduler, Logger logger,
                     Stats stats, BiConsumer<String, Verdict> onResult) {
        this.settings = settings;
        this.providers = List.copyOf(providers);
        this.scheduler = scheduler;
        this.logger = logger;
        this.stats = stats;
        this.onResult = onResult;
    }

    public List<AiProvider> providers() {
        return providers;
    }

    public boolean hasProviders() {
        return !providers.isEmpty();
    }

    /** True if the player may trigger another AI check now (also counts the check). */
    public boolean tryAcquirePlayer(UUID player) {
        if (player == null || settings.playerChecksPerMinute <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long[] w = playerWindows.computeIfAbsent(player, k -> new long[]{now, 0});
        synchronized (w) {
            if (now - w[0] >= 60_000) {
                w[0] = now;
                w[1] = 0;
            }
            if (w[1] >= settings.playerChecksPerMinute) {
                return false;
            }
            w[1]++;
            return true;
        }
    }

    public void forgetPlayer(UUID player) {
        playerWindows.remove(player);
    }

    /**
     * Queues a message for AI classification.
     *
     * @param key  normalized message (cache / de-duplication key)
     * @param text original message text that is sent to the model
     */
    public CompletableFuture<Verdict> submit(String key, String text) {
        CompletableFuture<Verdict> existing = inFlight.get(key);
        if (existing != null) {
            stats.aiDeduplicated.increment();
            return existing;
        }
        CompletableFuture<Verdict> future = new CompletableFuture<>();
        existing = inFlight.putIfAbsent(key, future);
        if (existing != null) {
            stats.aiDeduplicated.increment();
            return existing;
        }
        future.whenComplete((v, t) -> inFlight.remove(key, future));
        future.completeOnTimeout(fallback("timeout"), settings.hardTimeoutMs, TimeUnit.MILLISECONDS);

        String trimmed = text.length() > settings.maxMessageLength ? text.substring(0, settings.maxMessageLength) : text;
        Pending p = new Pending(key, trimmed, future);
        if (!settings.batchEnabled || settings.batchMax <= 1) {
            dispatch(List.of(p));
            return future;
        }
        queue.add(p);
        if (queued.incrementAndGet() >= batchLimit()) {
            flush();
        } else if (flushScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::flush, settings.batchWindowMs, TimeUnit.MILLISECONDS);
        }
        return future;
    }

    private int batchLimit() {
        int limit = settings.batchMax;
        for (AiProvider p : providers) {
            if (p.available()) {
                return Math.max(1, Math.min(limit, p.maxBatch()));
            }
        }
        return limit;
    }

    private void flush() {
        flushScheduled.set(false);
        List<Pending> batch = new ArrayList<>();
        Pending p;
        while ((p = queue.poll()) != null) {
            queued.decrementAndGet();
            batch.add(p);
        }
        if (batch.isEmpty()) {
            return;
        }
        int limit = Math.max(1, settings.batchMax);
        for (int i = 0; i < batch.size(); i += limit) {
            dispatch(batch.subList(i, Math.min(batch.size(), i + limit)));
        }
    }

    private boolean tryAcquireRequest() {
        if (settings.requestsPerMinute <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= 60_000 && windowStart.compareAndSet(start, now)) {
            windowRequests.set(0);
        }
        return windowRequests.incrementAndGet() <= settings.requestsPerMinute;
    }

    private void dispatch(List<Pending> batch) {
        if (!tryAcquireRequest()) {
            stats.rateLimited.add(batch.size());
            Verdict fb = fallback("rate-limit");
            batch.forEach(p -> p.future().complete(fb));
            return;
        }
        List<String> texts = batch.stream().map(Pending::text).toList();
        long start = System.currentTimeMillis();
        classifyChain(texts, 0).whenComplete((results, error) -> {
            if (error != null) {
                stats.aiErrors.increment();
                logger.log(Level.FINE, "[AI] all providers failed", error);
                Verdict fb = fallback("error");
                batch.forEach(p -> p.future().complete(fb));
                return;
            }
            stats.aiTotalLatencyMs.addAndGet(System.currentTimeMillis() - start);
            for (int i = 0; i < batch.size(); i++) {
                Pending p = batch.get(i);
                AiResult r = i < results.size() ? results.get(i) : null;
                if (r == null) {
                    p.future().complete(fallback("no-answer"));
                    continue;
                }
                Verdict v = toVerdict(r);
                try {
                    onResult.accept(p.key(), v);
                } catch (RuntimeException ex) {
                    logger.log(Level.WARNING, "[AI] result handler failed", ex);
                }
                p.future().complete(v);
            }
        });
    }

    private Verdict toVerdict(AiResult r) {
        boolean flagged = r.flagged() && r.score() >= settings.minConfidence;
        if (!flagged) {
            return new Verdict(false, List.of(), List.of(), Verdict.Source.AI, r.score(), r.provider());
        }
        return new Verdict(true, r.categories(), r.words(), Verdict.Source.AI, r.score(), r.provider());
    }

    public Verdict fallback(String reason) {
        if (settings.failOpen) {
            return Verdict.clean(Verdict.Source.FALLBACK, reason);
        }
        return new Verdict(true, List.of("unverified"), List.of(), Verdict.Source.FALLBACK, 0.0, reason);
    }

    /** Asks providers[from..] and merges answers according to the strategy. */
    private CompletableFuture<List<AiResult>> classifyChain(List<String> texts, int from) {
        int idx = nextAvailable(from);
        if (idx < 0) {
            return CompletableFuture.failedFuture(new AiException("No AI provider available"));
        }
        AiProvider provider = providers.get(idx);
        stats.aiRequests.increment();
        stats.aiMessages.add(texts.size());
        CompletableFuture<List<AiResult>> call;
        try {
            call = provider.classify(texts);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((results, error) -> {
            if (error != null) {
                stats.aiErrors.increment();
                logger.warning("[AI] Provider '" + provider.name() + "' failed: " + rootMessage(error));
                return classifyChain(texts, idx + 1);
            }
            List<Integer> retry = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                AiResult r = i < results.size() ? results.get(i) : null;
                if (r == null) {
                    retry.add(i);
                } else if (settings.strategy == Strategy.ESCALATE
                        && r.score() >= settings.uncertainMin && r.score() <= settings.uncertainMax) {
                    retry.add(i);
                }
            }
            if (retry.isEmpty() || nextAvailable(idx + 1) < 0) {
                return CompletableFuture.completedFuture(results);
            }
            List<String> sub = retry.stream().map(texts::get).toList();
            return classifyChain(sub, idx + 1).handle((subResults, subError) -> {
                AiResult[] merged = results.toArray(new AiResult[texts.size()]);
                if (subError == null) {
                    for (int k = 0; k < retry.size(); k++) {
                        AiResult r = k < subResults.size() ? subResults.get(k) : null;
                        if (r != null) {
                            merged[retry.get(k)] = r;
                        }
                    }
                }
                return Arrays.asList(merged);
            });
        }).thenCompose(f -> f);
    }

    private int nextAvailable(int from) {
        for (int i = from; i < providers.size(); i++) {
            if (providers.get(i).available()) {
                return i;
            }
        }
        return -1;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    public int inFlight() {
        return inFlight.size();
    }
}
