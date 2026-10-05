package dev.jkaanof.jchatcontrol.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionCacheTest {

    @TempDir
    Path dir;

    @Test
    void persistsAndEvicts() throws Exception {
        DecisionCache c = new DecisionCache(100, 0, 0);
        c.put("selam millet", Verdict.clean(Verdict.Source.AI, "x"));
        c.put("kotu soz", new Verdict(true, List.of("insult"), List.of("soz"), Verdict.Source.AI, 0.9, "x"));
        Path f = dir.resolve("cache.tsv");
        c.save(f);

        DecisionCache d = new DecisionCache(100, 0, 0);
        d.load(f);
        assertEquals(2, d.size());
        Verdict v = d.get("kotu soz");
        assertTrue(v.flagged());
        assertEquals(List.of("soz"), v.words());

        for (int i = 0; i < 150; i++) {
            d.put("m" + i, Verdict.clean(Verdict.Source.AI, "x"));
        }
        assertEquals(100, d.size());
        assertNull(d.get("selam millet"));
    }
}
