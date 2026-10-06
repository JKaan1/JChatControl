package dev.jkaanof.jchatcontrol.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpamGuardTest {

    private final TextNormalizer n = TestFixtures.normalizer();

    private String norm(String s) {
        return n.normalize(s).text();
    }

    @Test
    void chatDelay() {
        SpamGuard g = new SpamGuard(new SpamGuard.Settings());
        UUID p = UUID.randomUUID();
        assertEquals(SpamGuard.Type.OK, g.check(p, norm("selam"), 0, true).type());
        SpamGuard.Result r = g.check(p, norm("naber"), 500, true);
        assertEquals(SpamGuard.Type.DELAY, r.type());
        assertEquals(1000, r.remainingMs());
        assertEquals(SpamGuard.Type.OK, g.check(p, norm("naber"), 1600, true).type());
        // bypass delay
        assertEquals(SpamGuard.Type.OK, g.check(p, norm("iyiyim"), 1700, false).type());
    }

    @Test
    void duplicatesAndSimilarMessages() {
        SpamGuard g = new SpamGuard(new SpamGuard.Settings());
        UUID p = UUID.randomUUID();
        assertFalse(g.check(p, norm("gelin sunucuya katılın çok güzel"), 0, true).blocked());
        // same after normalization (case, repeats, punctuation)
        assertEquals(SpamGuard.Type.DUPLICATE, g.check(p, norm("GELİN sunucuya katılın çoook güzel!!!"), 2000, true).type());
        // very similar
        assertEquals(SpamGuard.Type.DUPLICATE, g.check(p, norm("gelin sunucuya katılın çok güzell x"), 4000, true).type());
        assertFalse(g.check(p, norm("başka bir konu"), 6000, true).blocked());
        // window passed
        assertFalse(g.check(p, norm("gelin sunucuya katılın çok güzel"), 40_000, true).blocked());
    }

    @Test
    void burstAndPunish() {
        SpamGuard.Settings s = new SpamGuard.Settings();
        s.delayMs = 0;
        s.duplicateEnabled = false;
        s.burstMessages = 3;
        s.burstWindowMs = 5000;
        s.punishThreshold = 2;
        SpamGuard g = new SpamGuard(s);
        UUID p = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertFalse(g.check(p, "m" + i, i * 100L, true).blocked());
        }
        SpamGuard.Result first = g.check(p, "m3", 400, true);
        assertEquals(SpamGuard.Type.BURST, first.type());
        assertFalse(first.punish());
        assertTrue(g.check(p, "m4", 500, true).punish(), "second block reaches the punish threshold");
        assertFalse(g.check(p, "m5", 6000, true).blocked(), "window passed");
    }

    @Test
    void similarity() {
        assertEquals(1.0, SpamGuard.similarity("abc", "abc"));
        assertTrue(SpamGuard.similarity("selam millet", "selam milet") > 0.9);
        assertTrue(SpamGuard.similarity("selam", "tamamen farkli bir cumle") < 0.3);
    }
}
