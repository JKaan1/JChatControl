package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.core.Verdict;
import dev.jkaanof.jchatcontrol.util.Section;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolationCommandsTest {

    private static Verdict v(String category, Verdict.Source source) {
        return new Verdict(true, List.of(category), List.of("w"), source, 1.0, "d");
    }

    private static ViolationCommands load() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/config.yml"))) {
            Map<String, Object> root = new Yaml().load(in);
            return new ViolationCommands(new Section(root).getSection("violation-commands"));
        }
    }

    @Test
    void separateCommandsPerSource() throws Exception {
        ViolationCommands c = load();
        assertEquals(List.of("jcc mute {player} 2m Küfür (kelime filtresi)"), c.resolve(v("profanity", Verdict.Source.WORD_LIST)));
        assertEquals(List.of("jcc mute {player} 2m Küfür (kalıp)"), c.resolve(v("profanity", Verdict.Source.PATTERN)));
        assertEquals(List.of("jcc mute {player} 5m Küfür (yapay zeka)"), c.resolve(v("profanity", Verdict.Source.AI)));
        // cached AI decision uses the AI commands when no "cache" list exists
        assertEquals(c.resolve(v("profanity", Verdict.Source.AI)), c.resolve(v("profanity", Verdict.Source.CACHE)));
    }

    @Test
    void fallsBackToDefaultAndIgnoresNonViolations() throws Exception {
        ViolationCommands c = load();
        // politics has no own list -> default (empty)
        assertTrue(c.resolve(v("politics", Verdict.Source.AI)).isEmpty());
        assertTrue(c.resolve(v("profanity", Verdict.Source.FALLBACK)).isEmpty());
        assertTrue(c.resolve(Verdict.clean(Verdict.Source.AI, "x")).isEmpty());
    }

    @Test
    void categoryOverridesDefault() {
        Map<String, Object> cfg = Map.of(
                "default", Map.of("ai", List.of("warn {player}"), "regex", List.of("kick {player}")),
                "categories", Map.of("racism", Map.of("ai", List.of("ban {player}"))));
        ViolationCommands c = new ViolationCommands(new Section(cfg));
        assertEquals(List.of("ban {player}"), c.resolve(v("racism", Verdict.Source.AI)));
        assertEquals(List.of("kick {player}"), c.resolve(v("racism", Verdict.Source.PATTERN)));
        assertEquals(List.of("warn {player}"), c.resolve(v("insult", Verdict.Source.AI)));
    }
}
