package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.core.Action;
import dev.jkaanof.jchatcontrol.core.CategorySettings;
import dev.jkaanof.jchatcontrol.core.DecisionCache;
import dev.jkaanof.jchatcontrol.core.FilterEngine;
import dev.jkaanof.jchatcontrol.core.Stats;
import dev.jkaanof.jchatcontrol.core.TextNormalizer;
import dev.jkaanof.jchatcontrol.core.ai.AiProvider;
import dev.jkaanof.jchatcontrol.core.ai.AiService;
import dev.jkaanof.jchatcontrol.core.ai.PromptBuilder;
import dev.jkaanof.jchatcontrol.core.ai.provider.ProviderContext;
import dev.jkaanof.jchatcontrol.core.ai.provider.ProviderFactory;
import dev.jkaanof.jchatcontrol.core.learn.LearningManager;
import dev.jkaanof.jchatcontrol.core.match.PatternRule;
import dev.jkaanof.jchatcontrol.core.match.WordLists;
import dev.jkaanof.jchatcontrol.util.FileUtil;
import dev.jkaanof.jchatcontrol.util.Section;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * JChatControl - AI assisted chat moderation for Minecraft 1.21+.
 *
 * @author JKaanOF
 */
public final class JChatControl extends JavaPlugin {

    /** Bukkit side settings (the core engine has its own). */
    public static final class Settings {
        public boolean chatEnabled = true;
        public ChatListener.Mode chatMode = ChatListener.Mode.BLOCKING;
        public EventPriority chatPriority = EventPriority.LOWEST;
        public long blockingTimeoutMs = 3000;
        public boolean commandsEnabled = true;
        public boolean commandsUseAi = true;
        public boolean muteBlocksCommands = true;
        public Map<String, Integer> commands = ContentListener.defaultCommands();
        public boolean signsEnabled = true;
        public boolean booksEnabled = true;
        public boolean allowPlayerNames = true;
        public char censorChar = '*';
        public boolean notifyStaff = true;
        public boolean notifyConsole = true;
        public boolean punishmentsEnabled = true;
        public boolean capsEnabled = true;
        public int capsMinLength = 6;
        public int capsMaxPercent = 70;
        public Locale capsLocale = Locale.ROOT;
        public int maxRepeatedChars = 4;
        public long autosaveSeconds = 300;
    }

    private static final String[] DEFAULT_FILES = {
            "filters/allowed-words.txt", "filters/blocked-words.yml", "filters/suspicious-words.txt",
            "filters/patterns.yml", "lang/tr.yml", "lang/en.yml"
    };

    private volatile Settings settings = new Settings();
    private volatile FilterEngine engine;
    private volatile Lang lang;
    private volatile ViolationManager violations;
    private volatile ViolationLog violationLog;
    private MuteManager mutes;
    private Moderator moderator;
    private ChatListener chatListener;
    private final Stats stats = new Stats();

    private ExecutorService httpExecutor;
    private ScheduledExecutorService scheduler;
    private HttpClient http;
    private BukkitTask autosaveTask;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        AtomicInteger n = new AtomicInteger();
        httpExecutor = Executors.newFixedThreadPool(2, r -> daemon(r, "JChatControl-HTTP-" + n.incrementAndGet()));
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "JChatControl-Batch"));
        http = HttpClient.newBuilder()
                .executor(httpExecutor)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        mutes = new MuteManager(new File(getDataFolder(), "data/mutes.yml"));
        mutes.load();
        moderator = new Moderator(this);
        chatListener = new ChatListener(this);

        if (!reloadAll()) {
            getLogger().severe("Configuration could not be loaded, plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new ContentListener(this), this);
        PluginCommand cmd = getCommand("jchatcontrol");
        if (cmd != null) {
            JccCommand executor = new JccCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        getLogger().info("JChatControl " + getDescription().getVersion() + " by JKaanOF enabled.");
    }

    @Override
    public void onDisable() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        saveData();
        if (violationLog != null) {
            violationLog.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Saves learned words, AI cache and mutes. Thread-safe. */
    public synchronized void saveData() {
        FilterEngine e = engine;
        if (e != null) {
            try {
                if (e.learning() != null) {
                    e.learning().save();
                }
                e.cache().save(dataPath("data/ai-cache.tsv"));
            } catch (IOException ex) {
                getLogger().log(Level.WARNING, "Could not save learned data", ex);
            }
        }
        try {
            if (mutes != null) {
                mutes.save();
            }
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not save mutes", ex);
        }
    }

    private Path dataPath(String relative) {
        return getDataFolder().toPath().resolve(relative);
    }

    // ------------------------------------------------------------------ (re)loading

    /** Loads (or reloads) every file. Returns false on a fatal error; the old configuration stays active then. */
    public synchronized boolean reloadAll() {
        try {
            saveDefaultConfig();
            for (String f : DEFAULT_FILES) {
                if (!new File(getDataFolder(), f).exists()) {
                    saveResource(f, false);
                }
            }
            reloadConfig();
            Section cfg = ConfigUtil.toSection(getConfig());

            // persist the old engine's state before replacing it
            saveData();

            String language = cfg.getString("language", "tr");
            File langFile = new File(getDataFolder(), "lang/" + language + ".yml");
            if (!langFile.exists()) {
                langFile = new File(getDataFolder(), "lang/tr.yml");
            }
            Lang newLang = new Lang(langFile, getResource("lang/" + (langFile.getName())));

            Settings s = readSettings(cfg);
            FilterEngine newEngine = buildEngine(cfg);

            Map<Integer, List<String>> thresholds = new HashMap<>();
            Section th = cfg.getSection("punishments.thresholds");
            for (String key : th.keys()) {
                try {
                    thresholds.put(Integer.parseInt(key.trim()), th.getStringList(key));
                } catch (NumberFormatException ex) {
                    getLogger().warning("Invalid punishment threshold '" + key + "'");
                }
            }
            ViolationManager newViolations = new ViolationManager(
                    cfg.getLong("punishments.decay-minutes", 30) * 60_000L, thresholds);
            ViolationLog oldLog = violationLog;
            violationLog = new ViolationLog(dataPath("logs"), cfg.getBoolean("logging.violations", true), getLogger());
            if (oldLog != null) {
                oldLog.shutdown();
            }

            // keep points of online players over reloads
            ViolationManager old = violations;
            if (old != null) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    newViolations.add(p.getUniqueId(), old.points(p.getUniqueId()));
                }
            }

            this.settings = s;
            this.lang = newLang;
            this.engine = newEngine;
            this.violations = newViolations;

            if (s.allowPlayerNames) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    newEngine.lists().addRuntimeAllowed(p.getName());
                }
            }

            registerChatListener(s);
            scheduleAutosave(s);
            logSummary(newEngine);
            return true;
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Error while loading JChatControl configuration", ex);
            return engine != null;
        }
    }

    private void registerChatListener(Settings s) {
        HandlerList.unregisterAll(chatListener);
        // DELAYED mode must see the final chat format of other plugins
        EventPriority priority = s.chatMode == ChatListener.Mode.DELAYED ? EventPriority.HIGHEST : s.chatPriority;
        getServer().getPluginManager().registerEvent(AsyncPlayerChatEvent.class, chatListener, priority,
                chatListener, this, true);
    }

    private void scheduleAutosave(Settings s) {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        long ticks = Math.max(30, s.autosaveSeconds) * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveData, ticks, ticks);
    }

    private void logSummary(FilterEngine e) {
        StringBuilder sb = new StringBuilder("Loaded ");
        e.lists().sizes().forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
        sb.append("patterns=").append(e.patternCount()).append(" cache=").append(e.cache().size());
        getLogger().info(sb.toString());
        if (e.ai() != null && e.ai().hasProviders()) {
            List<String> names = e.ai().providers().stream().map(p -> p.name() + "(" + p.type() + ")").toList();
            getLogger().info("AI chain: " + String.join(" -> ", names) + " | trigger=" + e.settings().triggerMode
                    + " | mode=" + settings.chatMode);
        } else {
            getLogger().info("AI disabled - only local filters are active.");
        }
    }

    private Settings readSettings(Section cfg) {
        Settings s = new Settings();
        s.chatEnabled = cfg.getBoolean("chat.enabled", true);
        s.chatMode = parseEnum(ChatListener.Mode.class, cfg.getString("chat.mode", "BLOCKING"), ChatListener.Mode.BLOCKING);
        s.chatPriority = parseEnum(EventPriority.class, cfg.getString("chat.listener-priority", "LOWEST"), EventPriority.LOWEST);
        s.blockingTimeoutMs = cfg.getLong("chat.blocking-timeout-ms", 3000);
        s.commandsEnabled = cfg.getBoolean("private-messages.enabled", true);
        s.commandsUseAi = cfg.getBoolean("private-messages.use-ai", true);
        s.muteBlocksCommands = cfg.getBoolean("private-messages.mute-blocks", true);
        Section cmds = cfg.getSection("private-messages.commands");
        if (!cmds.keys().isEmpty()) {
            Map<String, Integer> m = new HashMap<>();
            for (String k : cmds.keys()) {
                m.put(k.toLowerCase(Locale.ROOT), cmds.getInt(k, 1));
            }
            s.commands = m;
        }
        s.signsEnabled = cfg.getBoolean("signs.enabled", true);
        s.booksEnabled = cfg.getBoolean("books.enabled", true);
        s.allowPlayerNames = cfg.getBoolean("filter.allow-player-names", true);
        String censor = cfg.getString("filter.censor-char", "*");
        s.censorChar = censor.isEmpty() ? '*' : censor.charAt(0);
        s.notifyStaff = cfg.getBoolean("notify.staff", true);
        s.notifyConsole = cfg.getBoolean("notify.console", true);
        s.punishmentsEnabled = cfg.getBoolean("punishments.enabled", true);
        s.capsEnabled = cfg.getBoolean("caps.enabled", true);
        s.capsMinLength = cfg.getInt("caps.min-length", 6);
        s.capsMaxPercent = cfg.getInt("caps.max-percent", 70);
        String capsLocale = cfg.getString("caps.locale", "");
        s.capsLocale = capsLocale.isBlank() ? Locale.ROOT : Locale.forLanguageTag(capsLocale);
        s.maxRepeatedChars = cfg.getInt("flood.max-repeated-chars", 4);
        s.autosaveSeconds = cfg.getLong("autosave-seconds", 300);
        return s;
    }

    private FilterEngine buildEngine(Section cfg) throws IOException {
        // normalizer
        TextNormalizer.Options no = new TextNormalizer.Options();
        no.leet = cfg.getBoolean("filter.normalize.leetspeak", true);
        no.collapseRepeats = cfg.getBoolean("filter.normalize.collapse-repeats", true);
        no.foldDiacritics = cfg.getBoolean("filter.normalize.fold-diacritics", true);
        no.homoglyphs = cfg.getBoolean("filter.normalize.homoglyphs", true);
        no.singleLetterRunMin = cfg.getInt("filter.spaced-letters-min", 3);
        TextNormalizer normalizer = new TextNormalizer(no);

        WordLists.StemOptions stem = new WordLists.StemOptions();
        stem.enabled = cfg.getBoolean("filter.allowed-stems.enabled", true);
        stem.minStemLength = cfg.getInt("filter.allowed-stems.min-stem-length", 4);
        stem.maxSuffixLength = cfg.getInt("filter.allowed-stems.max-suffix-length", 5);
        WordLists lists = new WordLists(normalizer, stem);

        // categories
        List<CategorySettings> categories = new ArrayList<>();
        Section cats = cfg.getSection("categories");
        for (String id : cats.keys()) {
            Section c = cats.getSection(id);
            categories.add(new CategorySettings(id.toLowerCase(Locale.ROOT), c.getBoolean("enabled", true),
                    c.getString("display-name", id), Action.parse(c.getString("action", "BLOCK"), Action.BLOCK),
                    c.getInt("points", 1), c.getString("ai-description", "")));
        }
        Set<String> categoryIds = new HashSet<>();
        categories.forEach(c -> categoryIds.add(c.id()));

        // word lists
        List<String> allowed = new ArrayList<>(FileUtil.readWordList(dataPath("filters/allowed-words.txt")));
        allowed.addAll(cfg.getStringList("filter.extra-allowed-words"));
        lists.setAllowedBase(allowed);
        lists.setSuspicious(FileUtil.readWordList(dataPath("filters/suspicious-words.txt")));
        lists.clearBlocked();
        YamlConfiguration blocked = YamlConfiguration.loadConfiguration(dataPath("filters/blocked-words.yml").toFile());
        for (String category : blocked.getKeys(false)) {
            ConfigurationSection sec = blocked.getConfigurationSection(category);
            if (sec == null) {
                continue;
            }
            String cat = category.toLowerCase(Locale.ROOT);
            if (!categoryIds.contains(cat)) {
                getLogger().warning("blocked-words.yml: unknown category '" + category + "' (add it to config.yml categories)");
            }
            for (String w : sec.getStringList("exact")) {
                lists.addBlocked(w, cat, WordLists.MatchType.EXACT);
            }
            for (String w : sec.getStringList("prefix")) {
                lists.addBlocked(w, cat, WordLists.MatchType.PREFIX);
            }
            for (String w : sec.getStringList("contains")) {
                lists.addBlocked(w, cat, WordLists.MatchType.CONTAINS);
            }
        }

        // patterns
        List<PatternRule> patterns = new ArrayList<>();
        YamlConfiguration pat = YamlConfiguration.loadConfiguration(dataPath("filters/patterns.yml").toFile());
        for (String category : pat.getKeys(false)) {
            String cat = category.toLowerCase(Locale.ROOT);
            addPatterns(patterns, cat, pat.getStringList(category + ".normalized"), false);
            addPatterns(patterns, cat, pat.getStringList(category + ".raw"), true);
        }

        // learning
        Stats st = stats;
        LearningManager.Settings ls = new LearningManager.Settings();
        ls.enabled = cfg.getBoolean("learning.enabled", true);
        ls.learnAllowed = cfg.getBoolean("learning.allowed-words.enabled", true);
        ls.allowedThreshold = Math.max(1, cfg.getInt("learning.allowed-words.min-clean-sightings", 3));
        ls.learnBlocked = cfg.getBoolean("learning.blocked-words.enabled", true);
        ls.requireApproval = cfg.getBoolean("learning.blocked-words.require-approval", false);
        ls.blockedMinConfidence = cfg.getDouble("learning.blocked-words.min-confidence", 0.85);
        ls.minWordLength = cfg.getInt("learning.min-word-length", 3);
        ls.maxWordLength = cfg.getInt("learning.max-word-length", 24);
        ls.maxCandidates = cfg.getInt("learning.max-candidates", 50000);
        LearningManager learning = new LearningManager(ls, lists, st, getLogger(), dataPath("learned"));
        learning.load();
        lists.rebuild();

        // cache
        DecisionCache cache = new DecisionCache(cfg.getInt("cache.max-entries", 50000),
                cfg.getLong("cache.clean-ttl-hours", 24 * 30) * 3_600_000L,
                cfg.getLong("cache.flagged-ttl-hours", 24 * 30) * 3_600_000L);
        cache.load(dataPath("data/ai-cache.tsv"));

        // AI
        FilterEngine.Settings fs = new FilterEngine.Settings();
        fs.checkSingleLetterRuns = no.singleLetterRunMin > 1;
        fs.checkCompact = cfg.getBoolean("filter.check-compact", false);
        fs.aiEnabled = cfg.getBoolean("ai.enabled", true);
        fs.triggerMode = parseEnum(FilterEngine.TriggerMode.class, cfg.getString("ai.trigger", "UNKNOWN_WORDS"),
                FilterEngine.TriggerMode.UNKNOWN_WORDS);
        fs.minLetters = cfg.getInt("ai.min-letters", 3);
        fs.maxUnknownWordsWithoutAi = cfg.getInt("ai.max-unknown-words-without-ai", 0);
        fs.cacheEnabled = cfg.getBoolean("cache.enabled", true);

        AtomicReference<FilterEngine> ref = new AtomicReference<>();
        AiService ai = null;
        if (fs.aiEnabled) {
            String defaultCategory = cfg.getString("ai.default-category", "profanity");
            PromptBuilder prompt = new PromptBuilder(cfg.getString("ai.prompt.template", ""), categories,
                    cfg.getStringList("ai.prompt.languages"), cfg.getString("ai.prompt.extra-rules", ""));
            ProviderContext ctx = new ProviderContext(http, prompt, categoryIds, defaultCategory, getLogger(),
                    cfg.getBoolean("ai.debug", false));
            List<AiProvider> chain = new ArrayList<>();
            Section providers = cfg.getSection("ai.providers");
            for (String name : cfg.getStringList("ai.chain")) {
                Section p = providers.getSection(name);
                if (p.keys().isEmpty()) {
                    getLogger().warning("ai.chain references unknown provider '" + name + "'");
                    continue;
                }
                if (!p.getBoolean("enabled", true)) {
                    continue;
                }
                try {
                    chain.add(ProviderFactory.create(name, p, ctx));
                } catch (RuntimeException ex) {
                    getLogger().warning("Provider '" + name + "' skipped: " + ex.getMessage());
                }
            }
            AiService.Settings as = new AiService.Settings();
            as.batchEnabled = cfg.getBoolean("ai.batch.enabled", true);
            as.batchWindowMs = cfg.getLong("ai.batch.window-ms", 150);
            as.batchMax = cfg.getInt("ai.batch.max-messages", 10);
            as.requestsPerMinute = cfg.getInt("ai.limits.requests-per-minute", 60);
            as.playerChecksPerMinute = cfg.getInt("ai.limits.player-checks-per-minute", 6);
            as.strategy = parseEnum(AiService.Strategy.class, cfg.getString("ai.strategy", "FALLBACK"), AiService.Strategy.FALLBACK);
            as.uncertainMin = cfg.getDouble("ai.escalation.uncertain-min", 0.35);
            as.uncertainMax = cfg.getDouble("ai.escalation.uncertain-max", 0.75);
            as.minConfidence = cfg.getDouble("ai.min-confidence", 0.6);
            as.maxMessageLength = cfg.getInt("ai.max-message-length", 256);
            as.failOpen = !cfg.getString("ai.on-failure", "ALLOW").equalsIgnoreCase("BLOCK");
            as.hardTimeoutMs = cfg.getLong("ai.hard-timeout-ms", 15000);
            ai = new AiService(as, chain, scheduler, getLogger(), st, (key, v) -> {
                FilterEngine e = ref.get();
                if (e != null) {
                    e.onAiResult(key, v);
                }
            });
        }
        FilterEngine e = new FilterEngine(fs, normalizer, lists, patterns, categories, cache, ai, learning, st);
        ref.set(e);
        return e;
    }

    private void addPatterns(List<PatternRule> out, String category, List<String> regexes, boolean raw) {
        for (String r : regexes) {
            try {
                out.add(new PatternRule(category, Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), raw));
            } catch (PatternSyntaxException ex) {
                getLogger().warning("Invalid regex in patterns.yml (" + category + "): " + r + " -> " + ex.getDescription());
            }
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E def) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (RuntimeException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ message pre-processing

    /** Cheap cosmetic fixes before filtering: excessive caps and character floods. */
    public String preprocess(String message) {
        Settings s = settings;
        String out = message;
        if (s.maxRepeatedChars > 0) {
            out = limitRepeats(out, s.maxRepeatedChars);
        }
        if (s.capsEnabled && out.length() >= s.capsMinLength) {
            int letters = 0;
            int upper = 0;
            for (int i = 0; i < out.length(); i++) {
                char c = out.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                    if (Character.isUpperCase(c)) {
                        upper++;
                    }
                }
            }
            if (letters >= s.capsMinLength && upper * 100 > letters * s.capsMaxPercent) {
                out = out.toLowerCase(s.capsLocale);
            }
        }
        return out;
    }

    private static String limitRepeats(String s, int max) {
        StringBuilder sb = null;
        int run = 0;
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            run = c == prev ? run + 1 : 1;
            prev = c;
            if (run > max) {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                    sb.append(s, 0, i);
                }
                continue;
            }
            if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    // ------------------------------------------------------------------ accessors

    public Settings settings() {
        return settings;
    }

    public FilterEngine engine() {
        return engine;
    }

    public Lang lang() {
        return lang;
    }

    public Moderator moderator() {
        return moderator;
    }

    public MuteManager mutes() {
        return mutes;
    }

    public ViolationManager violations() {
        return violations;
    }

    public ViolationLog violationLog() {
        return violationLog;
    }

    public Stats stats() {
        return stats;
    }
}
