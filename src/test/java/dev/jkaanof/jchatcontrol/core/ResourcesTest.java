package dev.jkaanof.jchatcontrol.core;

import dev.jkaanof.jchatcontrol.core.ai.AiProvider;
import dev.jkaanof.jchatcontrol.core.ai.PromptBuilder;
import dev.jkaanof.jchatcontrol.core.ai.provider.ProviderContext;
import dev.jkaanof.jchatcontrol.core.ai.provider.ProviderFactory;
import dev.jkaanof.jchatcontrol.util.Section;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped config / language files must be valid and complete. */
class ResourcesTest {

    private static Map<String, Object> yaml(String file) throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources", file))) {
            return new Yaml().load(in);
        }
    }

    @Test
    void everyProviderInConfigCanBeCreated() throws Exception {
        Section cfg = new Section(yaml("config.yml"));
        Section cats = cfg.getSection("categories");
        assertEquals(TestFixtures.CATEGORIES, cats.keys());
        PromptBuilder prompt = new PromptBuilder("", TestFixtures.categories(), List.of("Turkish"), "");
        ProviderContext ctx = new ProviderContext(HttpClient.newHttpClient(), prompt, Set.copyOf(TestFixtures.CATEGORIES),
                "profanity", Logger.getLogger("t"), false);
        Section providers = cfg.getSection("ai.providers");
        assertFalse(providers.keys().isEmpty());
        for (String name : providers.keys()) {
            AiProvider p = ProviderFactory.create(name, providers.getSection(name), ctx);
            assertTrue(p.maxBatch() >= 1, name);
        }
        for (String name : cfg.getStringList("ai.chain")) {
            assertTrue(providers.keys().contains(name), "chain entry " + name);
        }
    }

    @Test
    void languagesHaveTheSameKeys() throws Exception {
        Map<String, Object> tr = yaml("lang/tr.yml");
        Map<String, Object> en = yaml("lang/en.yml");
        assertEquals(tr.keySet(), en.keySet());
    }

    @Test
    void promptContainsEnabledCategories() {
        String p = new PromptBuilder("", TestFixtures.categories(), List.of("Turkish", "English"), "No spoilers.").systemPrompt();
        assertTrue(p.contains("- racism: desc racism"));
        assertTrue(p.contains("Turkish, English"));
        assertTrue(p.contains("No spoilers."));
        assertFalse(p.contains("{categories}"));
    }
}
