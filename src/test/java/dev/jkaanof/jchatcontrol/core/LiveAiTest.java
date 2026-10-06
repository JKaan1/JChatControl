package dev.jkaanof.jchatcontrol.core;

import dev.jkaanof.jchatcontrol.core.ai.AiProvider;
import dev.jkaanof.jchatcontrol.core.ai.AiService;
import dev.jkaanof.jchatcontrol.core.ai.PromptBuilder;
import dev.jkaanof.jchatcontrol.core.ai.provider.ProviderContext;
import dev.jkaanof.jchatcontrol.core.ai.provider.ProviderFactory;
import dev.jkaanof.jchatcontrol.util.Section;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real pipeline against a real AI provider. Skipped unless JCC_LIVE_API_KEY is set.
 *
 * <pre>
 * JCC_LIVE_API_KEY=sk-or-...  JCC_LIVE_MODEL=meta-llama/llama-3.1-8b-instruct  mvn test -Dtest=LiveAiTest
 * optional: JCC_LIVE_BASE_URL (default https://openrouter.ai/api/v1), JCC_LIVE_TYPE (default openai),
 *           JCC_LIVE_PARSER (json | guard | yes-no | label)
 * </pre>
 * Writes a report to target/live-ai-report.txt. Only fails when the provider cannot be used at all;
 * model mistakes are reported, not failed.
 */
@EnabledIfEnvironmentVariable(named = "JCC_LIVE_API_KEY", matches = ".+")
class LiveAiTest {

    @TempDir
    Path dir;

    /** message -> expected category ("" = clean). All of these pass the local filters and need the AI. */
    private static final Map<String, String> SAMPLES = new LinkedHashMap<>();

    static {
        // clean
        SAMPLES.put("bugün yeni bir çiftlik kurdum gelin bakın", "");
        SAMPLES.put("kanka elmasları hangi sandığa koydun", "");
        SAMPLES.put("allahım bu ne lag ya", "");
        SAMPLES.put("ramazan bayramınız kutlu olsun herkese", "");
        SAMPLES.put("kurtlarımı evcilleştirdim çok tatlılar", "");
        SAMPLES.put("türkiyeden oynuyorum ping 40", "");
        SAMPLES.put("who wants to trade emeralds for netherite", "");
        // violations
        SAMPLES.put("senin gibi kürtler bu sunucuda olmamalı", "racism");
        SAMPLES.put("suriyeliler defolsun gitsin ülkemizden", "racism");
        SAMPLES.put("peygamberinize de dininize de sövüyorum", "religion");
        SAMPLES.put("seçimde herkes erdoğana oy versin chp hainleri", "politics");
        SAMPLES.put("türkçe konuşmayan herkes geri gitsin ülkesine kürtçe yasaklansın", "language");
        SAMPLES.put("ananı bacını iyi bilirim ben senin", "profanity");
        SAMPLES.put("seni bulup kafanı keseceğim adresini biliyorum", "threats");
        SAMPLES.put("mq sg lan buradan", "profanity");
        SAMPLES.put("ezik çocuk git annene ağla", "insult");
        SAMPLES.put("you are a worthless piece of garbage", "insult");
    }

    @Test
    void liveModel() throws Exception {
        String key = System.getenv("JCC_LIVE_API_KEY");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("type", env("JCC_LIVE_TYPE", "openai"));
        cfg.put("base-url", env("JCC_LIVE_BASE_URL", "https://openrouter.ai/api/v1"));
        cfg.put("model", env("JCC_LIVE_MODEL", "meta-llama/llama-3.1-8b-instruct"));
        cfg.put("api-key", key);
        cfg.put("parser", env("JCC_LIVE_PARSER", "json"));
        cfg.put("timeout-ms", 30000);
        cfg.put("max-tokens", 400);
        // some routers / models reject response_format
        cfg.put("json-response-format", Boolean.parseBoolean(env("JCC_LIVE_JSON_FORMAT", "true")));

        List<CategorySettings> categories = TestFixtures.categories();
        PromptBuilder prompt = new PromptBuilder("", categories, List.of("Turkish", "English"), "");
        ProviderContext ctx = new ProviderContext(HttpClient.newHttpClient(), prompt, Set.copyOf(TestFixtures.CATEGORIES),
                "profanity", Logger.getLogger("live"), Boolean.parseBoolean(env("JCC_LIVE_DEBUG", "false")));
        AiProvider provider = ProviderFactory.create("live", new Section(cfg), ctx);

        AiService.Settings as = new AiService.Settings();
        as.batchWindowMs = 300;
        as.playerChecksPerMinute = 0;
        as.requestsPerMinute = 0;
        as.hardTimeoutMs = 60000;
        FilterEngine engine = TestFixtures.engine(List.of(provider), as, dir);

        List<String> messages = new ArrayList<>(SAMPLES.keySet());
        List<FilterEngine.Evaluation> evals = new ArrayList<>();
        long start = System.currentTimeMillis();
        for (String m : messages) {
            evals.add(engine.evaluate(m, null, true));
        }
        StringBuilder report = new StringBuilder();
        report.append("model: ").append(cfg.get("model")).append(" @ ").append(cfg.get("base-url")).append('\n');
        int correct = 0;
        int fallbacks = 0;
        for (int i = 0; i < messages.size(); i++) {
            FilterEngine.Evaluation ev = evals.get(i);
            Verdict v = ev.isFinal() ? ev.verdict() : ev.pending().get(90, TimeUnit.SECONDS);
            String expected = SAMPLES.get(messages.get(i));
            boolean ok = expected.isEmpty() ? !v.flagged() : v.flagged();
            if (ok) {
                correct++;
            }
            if (v.source() == Verdict.Source.FALLBACK) {
                fallbacks++;
            }
            report.append(String.format("%s %-9s %-7s %-22s %-12s s=%.2f words=%s | %s%n", ok ? "OK  " : "MISS",
                    v.source(), v.flagged() ? "FLAG" : "clean", String.join(",", v.categories()),
                    expected.isEmpty() ? "(clean)" : "(" + expected + ")", v.confidence(), v.words(), messages.get(i)));
        }
        long took = System.currentTimeMillis() - start;
        Stats st = engine.stats();
        report.append(String.format("%ncorrect %d/%d | API requests %d for %d messages | errors %d | %d ms total%n",
                correct, messages.size(), st.aiRequests.sum(), st.aiMessages.sum(), st.aiErrors.sum(), took));

        // second round: everything must come from the cache / learned lists, no new API request
        long before = st.aiRequests.sum();
        for (String m : messages) {
            FilterEngine.Evaluation ev = engine.evaluate(m, null, true);
            if (!ev.isFinal()) {
                ev.pending().get(90, TimeUnit.SECONDS);
            }
        }
        report.append("second round extra API requests: ").append(st.aiRequests.sum() - before).append('\n');
        engine.learning().save();
        report.append("learned allowed: ").append(Files.readString(dir.resolve("allowed-words.txt")).lines()
                .filter(l -> !l.startsWith("#")).toList()).append('\n');
        report.append("learned blocked: ").append(Files.readString(dir.resolve("blocked-words.txt")).lines()
                .filter(l -> !l.startsWith("#")).toList()).append('\n');

        System.out.println(report);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/live-ai-report.txt"), report.toString());
        assertTrue(fallbacks < messages.size(), "provider never answered - check key / model / network:\n" + report);
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}
