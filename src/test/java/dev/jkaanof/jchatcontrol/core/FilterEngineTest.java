package dev.jkaanof.jchatcontrol.core;

import dev.jkaanof.jchatcontrol.core.ai.AiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterEngineTest {

    @TempDir
    Path dir;

    private static AiService.Settings noBatch() {
        AiService.Settings s = new AiService.Settings();
        s.batchEnabled = false;
        s.playerChecksPerMinute = 0;
        return s;
    }

    private static Verdict await(FilterEngine.Evaluation ev) throws Exception {
        return ev.isFinal() ? ev.verdict() : ev.pending().get(5, TimeUnit.SECONDS);
    }

    @Test
    void localStagesNeverCallTheAi() throws Exception {
        FakeProvider ai = new FakeProvider("fake", 10, m -> FakeProvider.clean());
        FilterEngine e = TestFixtures.engine(List.of(ai), noBatch(), dir);

        Verdict v = await(e.evaluate("naber kanka nasılsın", null, true));
        assertFalse(v.flagged());
        assertEquals(Verdict.Source.ALLOWED, v.source());

        v = await(e.evaluate("bu adam tam bir oruspu çocuğu", null, true));
        assertTrue(v.flagged());
        assertEquals(Verdict.Source.WORD_LIST, v.source());
        assertEquals("profanity", v.primaryCategory());

        v = await(e.evaluate("a m k", null, true));
        assertTrue(v.flagged());

        v = await(e.evaluate("gelin oyna.net sunucusuna", null, true));
        assertTrue(v.flagged());
        assertEquals("advertising", v.primaryCategory());

        v = await(e.evaluate("sen tam bir salaksın", null, true));
        assertTrue(v.flagged());

        v = await(e.evaluate("elmas kılıç satıyorum 64 demir karşılığında", null, true));
        assertFalse(v.flagged());

        assertTrue(ai.requests.isEmpty(), "no AI request expected: " + ai.requests);
    }

    @Test
    void unknownWordsGoToAiOnceThenCache() throws Exception {
        FakeProvider ai = new FakeProvider("fake", 10, m -> m.contains("zorbaa")
                ? FakeProvider.flagged("insult", 0.95, "zorbaa") : FakeProvider.clean());
        FilterEngine e = TestFixtures.engine(List.of(ai), noBatch(), dir);

        Verdict v = await(e.evaluate("bugün xyzqwe yaptım", null, true));
        assertFalse(v.flagged());
        assertEquals(Verdict.Source.AI, v.source());
        assertEquals(1, ai.requests.size());

        // same message with different case / repeats -> cache
        v = await(e.evaluate("BUGÜN xyzqweee YAPTIM!!", null, true));
        assertEquals(Verdict.Source.CACHE, v.source());
        assertEquals(1, ai.requests.size());

        // flagged word is learned -> next time blocked locally without AI
        v = await(e.evaluate("sen bir zorbaa", null, true));
        assertTrue(v.flagged());
        assertEquals(Verdict.Source.AI, v.source());
        v = await(e.evaluate("zorbaa herkes", null, true));
        assertTrue(v.flagged());
        assertEquals(Verdict.Source.WORD_LIST, v.source());
        assertEquals(2, ai.requests.size());
    }

    @Test
    void cleanWordsAreLearnedAfterThreshold() throws Exception {
        FakeProvider ai = new FakeProvider("fake", 10, m -> FakeProvider.clean());
        FilterEngine e = TestFixtures.engine(List.of(ai), noBatch(), dir);
        await(e.evaluate("bugün plorbit yaptım", null, true));
        await(e.evaluate("plorbit güzel", null, true));      // threshold 2 -> learned
        Verdict v = await(e.evaluate("plorbit iyi", null, true));
        assertEquals(Verdict.Source.ALLOWED, v.source());
        assertEquals(2, ai.requests.size());

        e.learning().save();
        assertTrue(java.nio.file.Files.readString(dir.resolve("allowed-words.txt")).contains("plorbit"));
    }

    @Test
    void suspiciousWordsAlwaysAskAi() throws Exception {
        FakeProvider ai = new FakeProvider("fake", 10, m -> FakeProvider.flagged("religion", 0.9));
        FilterEngine e = TestFixtures.engine(List.of(ai), noBatch(), dir);
        Verdict v = await(e.evaluate("allahını seveni", null, true));
        assertTrue(v.flagged());
        assertEquals("religion", v.primaryCategory());
        assertEquals(1, ai.requests.size());
    }

    @Test
    void lowConfidenceIsClean() throws Exception {
        FakeProvider ai = new FakeProvider("fake", 10, m -> FakeProvider.flagged("insult", 0.3, "blorp"));
        FilterEngine e = TestFixtures.engine(List.of(ai), noBatch(), dir);
        assertFalse(await(e.evaluate("blorp blorp", null, true)).flagged());
    }

    @Test
    void batchingAndDeduplication() throws Exception {
        FakeProvider ai = new FakeProvider("fake", 10, m -> FakeProvider.clean());
        AiService.Settings s = new AiService.Settings();
        s.batchWindowMs = 100;
        s.playerChecksPerMinute = 0;
        FilterEngine e = TestFixtures.engine(List.of(ai), s, dir);
        CompletableFuture<?> a = e.evaluate("qwzx plmk", null, true).pending();
        CompletableFuture<?> b = e.evaluate("zxcv bnmq", null, true).pending();
        CompletableFuture<?> c = e.evaluate("QWZX plmkkk!", null, true).pending(); // duplicate of a
        CompletableFuture.allOf(a, b, c).get(5, TimeUnit.SECONDS);
        assertEquals(1, ai.requests.size(), "one batched request");
        assertEquals(2, ai.requests.get(0).size(), "duplicate removed");
    }

    @Test
    void fallbackChainAndEscalation() throws Exception {
        FakeProvider first = new FakeProvider("cheap", 10, m -> new dev.jkaanof.jchatcontrol.core.ai.AiResult(
                false, List.of(), List.of(), m.contains("hmm") ? 0.5 : 0.05, ""));
        FakeProvider second = new FakeProvider("big", 10, m -> FakeProvider.flagged("politics", 0.9));
        AiService.Settings s = noBatch();
        s.strategy = AiService.Strategy.ESCALATE;
        FilterEngine e = TestFixtures.engine(List.of(first, second), s, dir);

        assertFalse(await(e.evaluate("zzkk ppll", null, true)).flagged());
        assertEquals(0, second.requests.size(), "confident answer is not escalated");

        Verdict v = await(e.evaluate("hmm zzkk", null, true));
        assertTrue(v.flagged(), "uncertain answer escalated to the second provider");
        assertEquals("big", v.detail());

        first.fail = true;
        v = await(e.evaluate("brandnew wordz", null, true));
        assertTrue(v.flagged());
        assertEquals("big", v.detail(), "falls back when the first provider fails");
    }

    @Test
    void playerLimitAndFailOpen() throws Exception {
        FakeProvider ai = new FakeProvider("fake", 10, m -> FakeProvider.clean());
        AiService.Settings s = noBatch();
        s.playerChecksPerMinute = 1;
        FilterEngine e = TestFixtures.engine(List.of(ai), s, dir);
        UUID p = UUID.randomUUID();
        assertNotNull(e.evaluate("abcabc defdef", p, true).pending());
        Verdict v = await(e.evaluate("ghighi jkljkl", p, true));
        assertEquals(Verdict.Source.FALLBACK, v.source());
        assertFalse(v.flagged());
    }

    @Test
    void censorReplacesOnlyBadWords() throws Exception {
        FilterEngine e = TestFixtures.engine(List.of(), noBatch(), dir);
        FilterEngine.Evaluation ev = e.evaluate("naber amk nasılsın", null, false);
        assertTrue(ev.verdict().flagged());
        assertEquals("naber *** nasılsın", e.censor(ev.normalized(), ev.verdict().words(), '*'));
        assertNull(e.censor(ev.normalized(), List.of(), '*'));
    }
}
