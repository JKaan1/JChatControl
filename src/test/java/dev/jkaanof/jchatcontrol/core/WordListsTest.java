package dev.jkaanof.jchatcontrol.core;

import dev.jkaanof.jchatcontrol.core.match.AhoCorasick;
import dev.jkaanof.jchatcontrol.core.match.WordLists;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordListsTest {

    @Test
    void ahoCorasickFindsAll() {
        AhoCorasick ac = new AhoCorasick(List.of("he", "she", "his", "hers"));
        assertEquals(List.of("she", "he", "hers"), ac.findAll("ushers"));
        assertEquals("she", ac.findFirst("ushers"));
        assertNull(ac.findFirst("xyz"));
    }

    @Test
    void defaultListsMatchBadWords() throws Exception {
        TextNormalizer n = TestFixtures.normalizer();
        WordLists l = TestFixtures.lists(n);
        for (String bad : List.of("amk", "AQ", "orospuçocuğu", "siktirgit", "yarrağı", "s1kt1r", "fuuuck", "salaksın",
                "pezevenk", "nigga", "ibneler")) {
            assertNotNull(l.matchToken(n.normalizeWord(bad)), bad);
        }
    }

    @Test
    void defaultListsDoNotHitCleanWords() throws Exception {
        TextNormalizer n = TestFixtures.normalizer();
        WordLists l = TestFixtures.lists(n);
        // classic false positives of Turkish folding / substring matching
        for (String ok : List.of("kayarak", "koyarak", "şikayet", "sıkıntı", "sıkıcı", "sıkıştım", "ananas", "kahve",
                "amca", "sikke", "Nigeria", "scunthorpe", "got", "picture", "yaparak", "malzeme", "assassin")) {
            assertNull(l.matchToken(n.normalizeWord(ok)), ok);
        }
    }

    @Test
    void allowedStemDoesNotWhitelistLongerBadWord() throws Exception {
        TextNormalizer n = TestFixtures.normalizer();
        WordLists l = TestFixtures.lists(n);
        l.addAllowedLearned("yara");
        // "yara" (wound) + "k" must not whitelist "yarak"
        assertNotNull(l.matchToken("yarak"));
        assertTrue(l.isAllowed("yaralar"));
    }

    @Test
    void stemsAndSuspicious() throws Exception {
        TextNormalizer n = TestFixtures.normalizer();
        WordLists l = TestFixtures.lists(n);
        assertTrue(l.isAllowed(n.normalizeWord("oyunlarımız")));
        assertTrue(l.isAllowed(n.normalizeWord("elmaslar")));
        assertFalse(l.isAllowed(n.normalizeWord("qwertyzx")));
        assertTrue(l.isSuspicious(n.normalizeWord("allahım")));
        assertTrue(l.isSuspicious(n.normalizeWord("din")));
        assertFalse(l.isSuspicious(n.normalizeWord("dinle")));
        assertFalse(l.isSuspicious(n.normalizeWord("kurtarmak")));
    }
}
