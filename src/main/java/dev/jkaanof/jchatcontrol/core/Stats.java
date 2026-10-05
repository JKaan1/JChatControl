package dev.jkaanof.jchatcontrol.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Lock-free counters, shown with /jcc stats. */
public final class Stats {

    public final LongAdder checked = new LongAdder();
    public final LongAdder flagged = new LongAdder();
    public final LongAdder aiRequests = new LongAdder();
    public final LongAdder aiMessages = new LongAdder();
    public final LongAdder aiErrors = new LongAdder();
    public final LongAdder aiDeduplicated = new LongAdder();
    public final LongAdder rateLimited = new LongAdder();
    public final LongAdder learnedAllowed = new LongAdder();
    public final LongAdder learnedBlocked = new LongAdder();
    public final AtomicLong aiTotalLatencyMs = new AtomicLong();
    private final Map<Verdict.Source, LongAdder> bySource = new EnumMap<>(Verdict.Source.class);

    public Stats() {
        for (Verdict.Source s : Verdict.Source.values()) {
            bySource.put(s, new LongAdder());
        }
    }

    public void record(Verdict v) {
        checked.increment();
        if (v.flagged()) {
            flagged.increment();
        }
        bySource.get(v.source()).increment();
    }

    public long source(Verdict.Source s) {
        return bySource.get(s).sum();
    }

    /** Percentage of checked messages that did not need an API call. */
    public double savedPercent() {
        long total = checked.sum();
        if (total == 0) {
            return 100.0;
        }
        return 100.0 * (total - source(Verdict.Source.AI)) / total;
    }

    public long averageAiLatency() {
        long n = aiRequests.sum();
        return n == 0 ? 0 : aiTotalLatencyMs.get() / n;
    }

    public void reset() {
        checked.reset();
        flagged.reset();
        aiRequests.reset();
        aiMessages.reset();
        aiErrors.reset();
        aiDeduplicated.reset();
        rateLimited.reset();
        learnedAllowed.reset();
        learnedBlocked.reset();
        aiTotalLatencyMs.set(0);
        bySource.values().forEach(LongAdder::reset);
    }
}
