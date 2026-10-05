package dev.jkaanof.jchatcontrol.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextNormalizerTest {

    private final TextNormalizer n = TestFixtures.normalizer();

    @Test
    void foldsTurkishLeetAndRepeats() {
        assertEquals("siktir", n.normalizeWord("SİİİKTİR"));
        assertEquals("siktir", n.normalizeWord("$1kt1r"));
        assertEquals("orospu", n.normalizeWord("0r0$pu"));
        assertEquals("gerizekali", n.normalizeWord("Gerizekalı"));
        assertEquals("cocuk", n.normalizeWord("ÇOCUK"));
        assertEquals("ama", n.normalizeWord("аmа")); // cyrillic a
    }

    @Test
    void tokensAndCacheKey() {
        TextNormalizer.Normalized r = n.normalize("Selaaam   arkadaşlar!!! nasılsınız?");
        assertEquals("selam arkadaslar nasilsiniz", r.text());
        assertEquals(3, r.tokens().size());
        assertEquals("arkadaşlar", r.tokens().get(1).raw());
    }

    @Test
    void numbersAreNeutral() {
        TextNormalizer.Normalized r = n.normalize("1v1 at 100 coords x2");
        assertTrue(r.tokens().get(0).neutral());
        assertTrue(r.tokens().get(2).neutral());
        assertEquals("1v1", r.tokens().get(0).norm());
    }

    @Test
    void spacedLettersAreJoined() {
        assertEquals("amk", n.normalize("a m k").joins().get(0));
        assertEquals("siktir", n.normalize("s.i.k.t.i.r lan").joins().get(0));
        assertTrue(n.normalize("a ve b").joins().isEmpty());
    }
}
