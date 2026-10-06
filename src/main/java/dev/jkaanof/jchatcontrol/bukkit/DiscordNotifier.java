package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.core.Action;
import dev.jkaanof.jchatcontrol.core.SpamGuard;
import dev.jkaanof.jchatcontrol.core.TextNormalizer;
import dev.jkaanof.jchatcontrol.core.Verdict;
import dev.jkaanof.jchatcontrol.core.discord.DiscordEmbed;
import dev.jkaanof.jchatcontrol.core.discord.DiscordWebhook;
import dev.jkaanof.jchatcontrol.util.Section;
import org.bukkit.ChatColor;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

/**
 * Sends moderation events to Discord webhooks as embeds: violations, spam punishments, mutes / unmutes and
 * words learned from the AI. Every event type can use its own webhook (channel).
 */
public final class DiscordNotifier {

    public enum Event {
        VIOLATION("violation"), SPAM("spam"), MUTE("mute"), UNMUTE("unmute"), LEARNED("learned-word");

        final String key;

        Event(String key) {
            this.key = key;
        }
    }

    private final JChatControl plugin;
    private final boolean enabled;
    private final DiscordWebhook webhook;
    private final String defaultUrl;
    private final Map<Event, Boolean> eventEnabled = new HashMap<>();
    private final Map<Event, String> eventUrl = new HashMap<>();
    private final Map<String, String> categoryUrl = new HashMap<>();
    private final Set<String> violationSources = new HashSet<>();
    private final Set<String> violationCategories = new HashSet<>();
    private final Map<String, String> colors = new HashMap<>();
    private final boolean hideMessage;
    private final boolean spoilerWords;
    private final boolean censorMessage;
    private final String avatarTemplate;
    private final String serverName;
    private final String footerIcon;

    DiscordNotifier(JChatControl plugin, Section cfg, HttpClient http, ScheduledExecutorService scheduler, Logger logger) {
        this.plugin = plugin;
        this.defaultUrl = cfg.getString("webhook-url", "").trim();
        DiscordWebhook.Settings ws = new DiscordWebhook.Settings();
        ws.username = cfg.getString("username", "JChatControl");
        ws.avatarUrl = cfg.getString("avatar-url", "");
        ws.useEmbeds = cfg.getBoolean("embeds", true);
        ws.batchMs = Math.max(250, cfg.getLong("batch-ms", 2000));
        ws.maxQueue = cfg.getInt("max-queue", 200);
        this.webhook = new DiscordWebhook(ws, http, scheduler, logger);
        for (Event e : Event.values()) {
            Section es = cfg.getSection("events." + e.key);
            eventEnabled.put(e, es.getBoolean("enabled", e != Event.UNMUTE));
            eventUrl.put(e, es.getString("webhook-url", "").trim());
        }
        cfg.getStringMap("events.violation.category-webhooks").forEach((k, v) -> categoryUrl.put(k.toLowerCase(Locale.ROOT), v.trim()));
        cfg.getStringList("events.violation.sources").forEach(s -> violationSources.add(s.toLowerCase(Locale.ROOT)));
        cfg.getStringList("events.violation.categories").forEach(s -> violationCategories.add(s.toLowerCase(Locale.ROOT)));
        cfg.getStringMap("colors").forEach((k, v) -> colors.put(k.toLowerCase(Locale.ROOT), v));
        this.hideMessage = cfg.getBoolean("privacy.hide-message", false);
        this.spoilerWords = cfg.getBoolean("privacy.spoiler", true);
        this.censorMessage = cfg.getBoolean("privacy.censor-message", false);
        this.avatarTemplate = cfg.getString("player-avatar-url", "https://mc-heads.net/avatar/{uuid}/64");
        this.serverName = cfg.getString("server-name", "Minecraft");
        this.footerIcon = cfg.getString("footer-icon-url", "");
        boolean anyUrl = !defaultUrl.isEmpty() || eventUrl.values().stream().anyMatch(u -> !u.isEmpty());
        this.enabled = cfg.getBoolean("enabled", false) && anyUrl;
        if (cfg.getBoolean("enabled", false) && !anyUrl) {
            logger.warning("[Discord] enabled but no webhook-url is set - Discord notifications are off.");
        }
    }

    static DiscordNotifier disabled(JChatControl plugin, HttpClient http, ScheduledExecutorService scheduler, Logger logger) {
        return new DiscordNotifier(plugin, Section.empty(), http, scheduler, logger);
    }

    public boolean enabled() {
        return enabled;
    }

    public DiscordWebhook webhook() {
        return webhook;
    }

    private String url(Event e) {
        String u = eventUrl.get(e);
        return u == null || u.isEmpty() ? defaultUrl : u;
    }

    private boolean on(Event e) {
        return enabled && eventEnabled.getOrDefault(e, true) && !url(e).isEmpty();
    }

    private String color(String key, String def) {
        return colors.getOrDefault(key.toLowerCase(Locale.ROOT), def);
    }

    private DiscordEmbed base(String title, String colorKey, String defColor, String playerName, UUID uuid) {
        DiscordEmbed e = new DiscordEmbed()
                .title(title)
                .color(color(colorKey, colors.getOrDefault("default", defColor)))
                .footer(plugin.lang().plain("discord-footer", "server", serverName), footerIcon)
                .timestamp(Instant.now());
        if (uuid != null && avatarTemplate.startsWith("http")) {
            e.thumbnail(avatarTemplate.replace("{uuid}", uuid.toString()).replace("{player}", playerName));
        }
        return e;
    }

    private String words(List<String> words) {
        if (words.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            String esc = DiscordEmbed.escape(w);
            sb.append(spoilerWords ? "||" + esc + "||" : "`" + w.replace("`", "'") + "`");
        }
        return sb.toString();
    }

    private String message(String message, TextNormalizer.Normalized n, Verdict v) {
        if (hideMessage) {
            return null;
        }
        String text = message;
        if (censorMessage && n != null) {
            String c = plugin.engine().censor(n, v.words(), '*');
            if (c != null) {
                text = c;
            }
        }
        String esc = DiscordEmbed.escape(text);
        return spoilerWords && !censorMessage ? "||" + esc + "||" : esc;
    }

    private String stageName(Verdict.Source source) {
        ViolationCommands.Kind k = ViolationCommands.Kind.of(source);
        String key = k == null ? source.name().toLowerCase(Locale.ROOT) : k.key;
        return plugin.lang().plain("discord-stage-" + key);
    }

    // ------------------------------------------------------------------ events

    /** A violation (any stage). Safe from any thread. */
    public void violation(String player, UUID uuid, Verdict v, String message, TextNormalizer.Normalized n,
                          String context, Action action, int points) {
        if (!on(Event.VIOLATION)) {
            return;
        }
        ViolationCommands.Kind kind = ViolationCommands.Kind.of(v.source());
        if (!violationSources.isEmpty() && (kind == null || !violationSources.contains(kind.key))) {
            return;
        }
        String cat = v.primaryCategory();
        if (!violationCategories.isEmpty() && !violationCategories.contains(cat)) {
            return;
        }
        Lang lang = plugin.lang();
        String category = ChatColor.stripColor(plugin.moderator().displayName(cat));
        DiscordEmbed e = base(lang.plain("discord-violation-title", "category", category), cat, "#E74C3C", player, uuid)
                .field(lang.plain("discord-field-player"), DiscordEmbed.escape(player), true)
                .field(lang.plain("discord-field-category"), category, true)
                .field(lang.plain("discord-field-stage"), stageName(v.source()), true)
                .field(lang.plain("discord-field-action"), lang.plain("discord-action-" + action.name().toLowerCase(Locale.ROOT)), true)
                .field(lang.plain("discord-field-context"), "`" + context + "`", true)
                .field(lang.plain("discord-field-points"), Integer.toString(points), true);
        if (v.source() == Verdict.Source.AI || v.source() == Verdict.Source.CACHE) {
            e.field(lang.plain("discord-field-confidence"), String.format(Locale.ROOT, "%.0f%%", v.confidence() * 100), true);
            if (v.detail() != null && v.source() == Verdict.Source.AI) {
                e.field(lang.plain("discord-field-provider"), "`" + v.detail() + "`", true);
            }
        } else if (v.detail() != null) {
            e.field(lang.plain("discord-field-rule"), "`" + v.detail().replace("`", "'") + "`", false);
        }
        e.field(lang.plain("discord-field-words"), words(v.words()), false)
                .field(lang.plain("discord-field-message"), message(message, n, v), false);
        webhook.send(categoryUrl.getOrDefault(cat, url(Event.VIOLATION)), e);
    }

    public void spam(String player, UUID uuid, SpamGuard.Type type, String message, String context) {
        if (!on(Event.SPAM)) {
            return;
        }
        Lang lang = plugin.lang();
        DiscordEmbed e = base(lang.plain("discord-spam-title"), "spam", "#F39C12", player, uuid)
                .field(lang.plain("discord-field-player"), DiscordEmbed.escape(player), true)
                .field(lang.plain("discord-field-type"), lang.plain("discord-spam-" + type.name().toLowerCase(Locale.ROOT)), true)
                .field(lang.plain("discord-field-context"), "`" + context + "`", true);
        if (!hideMessage) {
            e.field(lang.plain("discord-field-message"), DiscordEmbed.escape(message), false);
        }
        webhook.send(url(Event.SPAM), e);
    }

    public void mute(String player, UUID uuid, String duration, String reason, String by) {
        if (!on(Event.MUTE)) {
            return;
        }
        Lang lang = plugin.lang();
        webhook.send(url(Event.MUTE), base(lang.plain("discord-mute-title"), "mute", "#8E44AD", player, uuid)
                .field(lang.plain("discord-field-player"), DiscordEmbed.escape(player), true)
                .field(lang.plain("discord-field-duration"), duration, true)
                .field(lang.plain("discord-field-by"), DiscordEmbed.escape(by), true)
                .field(lang.plain("discord-field-reason"), DiscordEmbed.escape(reason), false));
    }

    public void unmute(String player, UUID uuid, String by) {
        if (!on(Event.UNMUTE)) {
            return;
        }
        Lang lang = plugin.lang();
        webhook.send(url(Event.UNMUTE), base(lang.plain("discord-unmute-title"), "unmute", "#2ECC71", player, uuid)
                .field(lang.plain("discord-field-player"), DiscordEmbed.escape(player), true)
                .field(lang.plain("discord-field-by"), DiscordEmbed.escape(by), true));
    }

    /** A word the AI reported (learned directly or waiting for approval). */
    public void learned(String word, String category, boolean pending) {
        if (!on(Event.LEARNED)) {
            return;
        }
        Lang lang = plugin.lang();
        DiscordEmbed e = base(lang.plain(pending ? "discord-learned-pending-title" : "discord-learned-title"),
                "learned", "#3498DB", "", null)
                .field(lang.plain("discord-field-word"), spoilerWords ? "||" + DiscordEmbed.escape(word) + "||" : "`" + word + "`", true)
                .field(lang.plain("discord-field-category"), ChatColor.stripColor(plugin.moderator().displayName(category)), true);
        if (pending) {
            e.description(lang.plain("discord-learned-pending-hint", "word", word));
        }
        webhook.send(url(Event.LEARNED), e);
    }

    /** /jcc discord test */
    public boolean test(String by) {
        if (!enabled) {
            return false;
        }
        Lang lang = plugin.lang();
        String target = defaultUrl.isEmpty() ? url(Event.VIOLATION) : defaultUrl;
        webhook.send(target, base(lang.plain("discord-test-title"), "default", "#1ABC9C", by, null)
                .description(lang.plain("discord-test-description", "server", serverName))
                .field(lang.plain("discord-field-by"), DiscordEmbed.escape(by), true));
        return true;
    }
}
