package dev.jkaanof.jchatcontrol.core;

import dev.jkaanof.jchatcontrol.core.ai.AiProvider;
import dev.jkaanof.jchatcontrol.core.ai.AiService;
import dev.jkaanof.jchatcontrol.core.learn.LearningManager;
import dev.jkaanof.jchatcontrol.core.match.PatternRule;
import dev.jkaanof.jchatcontrol.core.match.WordLists;
import dev.jkaanof.jchatcontrol.util.FileUtil;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Builds an engine from the real default filter files shipped with the plugin. */
final class TestFixtures {

    static final Path RES = Path.of("src/main/resources/filters");
    static final List<String> CATEGORIES = List.of("profanity", "insult", "racism", "religion", "language",
            "politics", "sexual", "threats", "advertising");

    private TestFixtures() {
    }

    static TextNormalizer normalizer() {
        return new TextNormalizer(new TextNormalizer.Options());
    }

    @SuppressWarnings("unchecked")
    static WordLists lists(TextNormalizer normalizer) throws IOException {
        WordLists lists = new WordLists(normalizer, new WordLists.StemOptions());
        lists.setAllowedBase(FileUtil.readWordList(RES.resolve("allowed-words.txt")));
        lists.setSuspicious(FileUtil.readWordList(RES.resolve("suspicious-words.txt")));
        try (InputStream in = java.nio.file.Files.newInputStream(RES.resolve("blocked-words.yml"))) {
            Map<String, Object> root = new Yaml().load(in);
            for (Map.Entry<String, Object> e : root.entrySet()) {
                Map<String, List<String>> sec = (Map<String, List<String>>) e.getValue();
                sec.getOrDefault("exact", List.of()).forEach(w -> lists.addBlocked(w, e.getKey(), WordLists.MatchType.EXACT));
                sec.getOrDefault("prefix", List.of()).forEach(w -> lists.addBlocked(w, e.getKey(), WordLists.MatchType.PREFIX));
                sec.getOrDefault("contains", List.of()).forEach(w -> lists.addBlocked(w, e.getKey(), WordLists.MatchType.CONTAINS));
            }
        }
        lists.rebuild();
        return lists;
    }

    @SuppressWarnings("unchecked")
    static List<PatternRule> patterns() throws IOException {
        List<PatternRule> out = new ArrayList<>();
        try (InputStream in = java.nio.file.Files.newInputStream(RES.resolve("patterns.yml"))) {
            Map<String, Object> root = new Yaml().load(in);
            for (Map.Entry<String, Object> e : root.entrySet()) {
                Map<String, List<String>> sec = (Map<String, List<String>>) e.getValue();
                for (String r : sec.getOrDefault("normalized", List.of())) {
                    out.add(new PatternRule(e.getKey(), Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), false));
                }
                for (String r : sec.getOrDefault("raw", List.of())) {
                    out.add(new PatternRule(e.getKey(), Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), true));
                }
            }
        }
        return out;
    }

    static List<CategorySettings> categories() {
        List<CategorySettings> list = new ArrayList<>();
        for (String c : CATEGORIES) {
            list.add(new CategorySettings(c, true, c, c.equals("profanity") ? Action.CENSOR : Action.BLOCK, 1, "desc " + c));
        }
        return list;
    }

    /** Engine with the given AI providers (may be empty). */
    static FilterEngine engine(List<AiProvider> providers, AiService.Settings aiSettings, Path learnedDir) throws IOException {
        TextNormalizer n = normalizer();
        WordLists lists = lists(n);
        Stats stats = new Stats();
        LearningManager.Settings ls = new LearningManager.Settings();
        ls.allowedThreshold = 2;
        LearningManager learning = new LearningManager(ls, lists, stats, Logger.getLogger("test"), learnedDir);
        DecisionCache cache = new DecisionCache(1000, 0, 0);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<FilterEngine> ref = new AtomicReference<>();
        AiService ai = new AiService(aiSettings, providers, scheduler, Logger.getLogger("test"), stats,
                (k, v) -> ref.get().onAiResult(k, v));
        FilterEngine.Settings fs = new FilterEngine.Settings();
        FilterEngine e = new FilterEngine(fs, n, lists, patterns(), categories(), cache, ai, learning, stats);
        ref.set(e);
        return e;
    }
}
