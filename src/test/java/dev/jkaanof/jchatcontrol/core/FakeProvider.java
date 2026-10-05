package dev.jkaanof.jchatcontrol.core;

import dev.jkaanof.jchatcontrol.core.ai.AiProvider;
import dev.jkaanof.jchatcontrol.core.ai.AiResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Scripted provider that records every request. */
final class FakeProvider implements AiProvider {

    final String name;
    final int maxBatch;
    final Function<String, AiResult> answer;
    final List<List<String>> requests = new CopyOnWriteArrayList<>();
    volatile boolean fail;

    FakeProvider(String name, int maxBatch, Function<String, AiResult> answer) {
        this.name = name;
        this.maxBatch = maxBatch;
        this.answer = answer;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "fake";
    }

    @Override
    public int maxBatch() {
        return maxBatch;
    }

    @Override
    public CompletableFuture<List<AiResult>> classify(List<String> messages) {
        requests.add(List.copyOf(messages));
        if (fail) {
            return CompletableFuture.failedFuture(new RuntimeException("down"));
        }
        List<AiResult> out = new ArrayList<>();
        for (String m : messages) {
            AiResult r = answer.apply(m);
            out.add(r == null ? null : r.withProvider(name));
        }
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String status() {
        return "ok";
    }

    static AiResult clean() {
        return new AiResult(false, List.of(), List.of(), 0.0, "");
    }

    static AiResult flagged(String category, double score, String... words) {
        return new AiResult(true, List.of(category), List.of(words), score, "");
    }
}
