package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.core.FilterEngine;
import dev.jkaanof.jchatcontrol.core.Stats;
import dev.jkaanof.jchatcontrol.core.Verdict;
import dev.jkaanof.jchatcontrol.core.ai.AiProvider;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** /jchatcontrol (aliases: /jcc, /chatcontrol) */
public final class JccCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("help", "reload", "stats", "test", "simulate", "allow", "unallow", "block",
            "unblock", "learn", "cache", "ai", "mute", "unmute", "violations", "save");

    private final JChatControl plugin;

    JccCommand(JChatControl plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Lang lang = plugin.lang();
        if (!sender.hasPermission("jchatcontrol.admin")) {
            lang.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            lang.getList("help").forEach(sender::sendMessage);
            return true;
        }
        FilterEngine engine = plugin.engine();
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                long start = System.currentTimeMillis();
                boolean ok = plugin.reloadAll();
                lang.send(sender, ok ? "reloaded" : "reload-failed", "ms", System.currentTimeMillis() - start);
            }
            case "save" -> {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, plugin::saveData);
                lang.send(sender, "saved");
            }
            case "stats" -> stats(sender, engine);
            case "test" -> testCommand(sender, Arrays.copyOfRange(args, 1, args.length), "/jcc test");
            case "simulate" -> {
                if (args.length < 3) {
                    lang.send(sender, "usage", "usage", "/jcc simulate <player> <message>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    lang.send(sender, "player-not-found", "player", args[1]);
                    return true;
                }
                simulate(sender, target, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            case "allow", "unallow" -> {
                if (args.length < 2) {
                    lang.send(sender, "usage", "usage", "/jcc " + sub + " <word>");
                    return true;
                }
                if (sub.equals("allow")) {
                    String n = engine.learning().addAllowed(args[1]);
                    if (n != null) {
                        engine.cache().removeIf(v -> v.flagged() && v.words().stream()
                                .anyMatch(w -> engine.lists().normalize(w).equals(n)));
                    }
                    lang.send(sender, "word-allowed", "word", n);
                } else {
                    lang.send(sender, engine.learning().removeAllowed(args[1]) ? "word-unallowed" : "word-not-found",
                            "word", args[1]);
                }
            }
            case "block", "unblock" -> {
                if (args.length < 2) {
                    lang.send(sender, "usage", "usage", "/jcc " + sub + " <word> [category]");
                    return true;
                }
                if (sub.equals("block")) {
                    String category = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "profanity";
                    if (engine.category(category) == null) {
                        lang.send(sender, "unknown-category", "category", category,
                                "categories", String.join(", ", engine.categories().keySet()));
                        return true;
                    }
                    String n = engine.learning().addBlocked(args[1], category);
                    lang.send(sender, "word-blocked", "word", n, "category", category);
                } else {
                    lang.send(sender, engine.learning().removeBlocked(args[1]) ? "word-unblocked" : "word-not-found",
                            "word", args[1]);
                }
            }
            case "learn" -> learn(sender, engine, args);
            case "cache" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("clear")) {
                    int size = engine.cache().size();
                    engine.cache().clear();
                    lang.send(sender, "cache-cleared", "count", size);
                } else {
                    lang.send(sender, "cache-info", "count", engine.cache().size());
                }
            }
            case "ai" -> ai(sender, engine, args);
            case "mute" -> mute(sender, args);
            case "unmute" -> {
                if (args.length < 2) {
                    lang.send(sender, "usage", "usage", "/jcc unmute <player>");
                    return true;
                }
                OfflinePlayer target = offline(args[1]);
                boolean ok = target != null && plugin.mutes().unmute(target.getUniqueId());
                lang.send(sender, ok ? "unmuted" : "not-muted", "player", args[1]);
                if (ok && target.getPlayer() != null) {
                    lang.send(target.getPlayer(), "you-were-unmuted");
                }
            }
            case "violations" -> {
                if (args.length < 2) {
                    lang.send(sender, "usage", "usage", "/jcc violations <player> [reset]");
                    return true;
                }
                OfflinePlayer target = offline(args[1]);
                if (target == null) {
                    lang.send(sender, "player-not-found", "player", args[1]);
                    return true;
                }
                if (args.length > 2 && args[2].equalsIgnoreCase("reset")) {
                    plugin.violations().reset(target.getUniqueId());
                    lang.send(sender, "violations-reset", "player", args[1]);
                } else {
                    lang.send(sender, "violations-info", "player", args[1],
                            "points", plugin.violations().points(target.getUniqueId()));
                }
            }
            default -> lang.send(sender, "unknown-command");
        }
        return true;
    }

    private void stats(CommandSender sender, FilterEngine engine) {
        Lang lang = plugin.lang();
        Stats s = plugin.stats();
        lang.send(sender, "stats-header");
        lang.send(sender, "stats-line", "key", "Checked", "value", s.checked.sum() + " (flagged " + s.flagged.sum() + ")");
        StringBuilder src = new StringBuilder();
        for (Verdict.Source source : Verdict.Source.values()) {
            src.append(source.name().toLowerCase(Locale.ROOT)).append('=').append(s.source(source)).append(' ');
        }
        lang.send(sender, "stats-line", "key", "By stage", "value", src.toString().trim());
        lang.send(sender, "stats-line", "key", "Without API", "value", String.format(Locale.ROOT, "%.1f%%", s.savedPercent()));
        lang.send(sender, "stats-line", "key", "AI requests", "value", s.aiRequests.sum() + " (" + s.aiMessages.sum()
                + " messages, avg " + s.averageAiLatency() + "ms, errors " + s.aiErrors.sum() + ", dedup "
                + s.aiDeduplicated.sum() + ", rate-limited " + s.rateLimited.sum() + ")");
        lang.send(sender, "stats-line", "key", "Learned", "value", "allowed +" + s.learnedAllowed.sum() + ", blocked +"
                + s.learnedBlocked.sum() + ", pending " + engine.learning().pending().size() + ", candidates "
                + engine.learning().candidateCount());
        StringBuilder lists = new StringBuilder();
        engine.lists().sizes().forEach((k, v) -> lists.append(k).append('=').append(v).append(' '));
        lang.send(sender, "stats-line", "key", "Lists", "value", lists.toString().trim() + " patterns=" + engine.patternCount());
        lang.send(sender, "stats-line", "key", "AI cache", "value", Integer.toString(engine.cache().size()));
        if (engine.ai() != null) {
            for (AiProvider p : engine.ai().providers()) {
                lang.send(sender, "stats-line", "key", "AI " + p.name(), "value", p.type() + " - " + p.status());
            }
        }
    }

    /**
     * /jcc test [-l] <message>  and  /chattest [-l] <message>
     * -l = local filters only (word lists + regex), the AI is not asked.
     */
    void testCommand(CommandSender sender, String[] args, String usage) {
        boolean localOnly = args.length > 0 && (args[0].equalsIgnoreCase("-l") || args[0].equalsIgnoreCase("-local"));
        String[] rest = localOnly ? Arrays.copyOfRange(args, 1, args.length) : args;
        if (rest.length == 0) {
            plugin.lang().send(sender, "usage", "usage", usage + " [-l] <message>");
            return;
        }
        test(sender, plugin.engine(), String.join(" ", rest), !localOnly);
    }

    private void test(CommandSender sender, FilterEngine engine, String message, boolean useAi) {
        Lang lang = plugin.lang();
        Player self = sender instanceof Player p ? p : null;
        UUID id = self == null ? null : self.getUniqueId();
        // tests never count against the player's AI limit
        FilterEngine.Evaluation ev = engine.evaluate(ChatColor.stripColor(message), null, useAi);
        lang.send(sender, "test-normalized", "text", ev.normalized().text());
        CompletableFuture<Verdict> f = ev.isFinal() ? CompletableFuture.completedFuture(ev.verdict()) : ev.pending();
        if (!ev.isFinal()) {
            lang.send(sender, "test-waiting");
        }
        f.thenAccept(v -> Bukkit.getScheduler().runTask(plugin, () -> {
            String cats = v.categories().isEmpty() ? "-" : String.join(", ", v.categories());
            String words = v.words().isEmpty() ? "-" : String.join(", ", v.words());
            String censored = v.flagged() ? engine.censor(ev.normalized(), v.words(), plugin.settings().censorChar) : null;
            lang.send(sender, v.flagged() ? "test-flagged" : "test-clean",
                    "source", v.source().name().toLowerCase(Locale.ROOT), "categories", cats, "words", words,
                    "confidence", String.format(Locale.ROOT, "%.2f", v.confidence()),
                    "detail", v.detail() == null ? "-" : v.detail(),
                    "action", v.flagged() ? engine.actionFor(v, dev.jkaanof.jchatcontrol.core.Action.BLOCK).name() : "-",
                    "censored", censored == null ? "-" : censored);
            if (v.flagged()) {
                List<String> commands = plugin.moderator().plannedCommands(sender.getName(), id, v, message, "test");
                if (commands.isEmpty()) {
                    lang.send(sender, "test-no-commands");
                } else {
                    lang.send(sender, "test-commands-header", "count", commands.size());
                    commands.forEach(c -> lang.send(sender, "test-command-line", "command", c));
                }
            }
        }));
    }

    /** Runs the full chat moderation for a player as if they wrote the message (commands really run). */
    private void simulate(CommandSender sender, Player target, String message) {
        Lang lang = plugin.lang();
        FilterEngine engine = plugin.engine();
        FilterEngine.Evaluation ev = engine.evaluate(ChatColor.stripColor(message), null, true);
        CompletableFuture<Verdict> f = ev.isFinal() ? CompletableFuture.completedFuture(ev.verdict()) : ev.pending();
        if (!ev.isFinal()) {
            lang.send(sender, "test-waiting");
        }
        f.thenAccept(v -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!target.isOnline()) {
                return;
            }
            Moderator.Decision d = plugin.moderator().decide(target, v, ev.normalized(), "simulate");
            String result = !v.flagged() ? "clean" : d.cancel() ? "blocked" : d.replacement() != null ? "censored: "
                    + d.replacement() : "sent";
            lang.send(sender, "simulate-result", "player", target.getName(), "source",
                    v.source().name().toLowerCase(Locale.ROOT), "categories",
                    v.categories().isEmpty() ? "-" : String.join(", ", v.categories()), "result", result);
        }));
    }

    private void learn(CommandSender sender, FilterEngine engine, String[] args) {
        Lang lang = plugin.lang();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "approve" -> {
                if (args.length < 3) {
                    lang.send(sender, "usage", "usage", "/jcc learn approve <word>");
                    return;
                }
                lang.send(sender, engine.learning().approve(args[2]) ? "learn-approved" : "word-not-found", "word", args[2]);
            }
            case "deny" -> {
                if (args.length < 3) {
                    lang.send(sender, "usage", "usage", "/jcc learn deny <word>");
                    return;
                }
                lang.send(sender, engine.learning().deny(args[2]) ? "learn-denied" : "word-not-found", "word", args[2]);
            }
            case "approveall" -> {
                int count = 0;
                for (String w : engine.learning().pending().keySet()) {
                    if (engine.learning().approve(w)) {
                        count++;
                    }
                }
                lang.send(sender, "learn-approved-all", "count", count);
            }
            default -> {
                Map<String, String> pending = engine.learning().pending();
                lang.send(sender, "learn-header", "count", pending.size());
                int shown = 0;
                for (Map.Entry<String, String> e : pending.entrySet()) {
                    if (shown++ >= 30) {
                        break;
                    }
                    lang.send(sender, "learn-line", "word", e.getKey(), "category", e.getValue());
                }
            }
        }
    }

    private void ai(CommandSender sender, FilterEngine engine, String[] args) {
        Lang lang = plugin.lang();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "on", "off" -> {
                engine.settings().aiEnabled = action.equals("on");
                lang.send(sender, action.equals("on") ? "ai-enabled" : "ai-disabled");
            }
            default -> {
                lang.send(sender, "ai-status", "state", engine.settings().aiEnabled ? "ON" : "OFF",
                        "trigger", engine.settings().triggerMode.name(), "mode", plugin.settings().chatMode.name(),
                        "inflight", engine.ai() == null ? 0 : engine.ai().inFlight());
                if (engine.ai() != null) {
                    for (AiProvider p : engine.ai().providers()) {
                        lang.send(sender, "stats-line", "key", p.name(), "value", p.type() + " - " + p.status());
                    }
                }
            }
        }
    }

    private void mute(CommandSender sender, String[] args) {
        Lang lang = plugin.lang();
        if (args.length < 3) {
            lang.send(sender, "usage", "usage", "/jcc mute <player> <10m|1h|1d|perm> [reason]");
            return;
        }
        OfflinePlayer target = offline(args[1]);
        if (target == null) {
            lang.send(sender, "player-not-found", "player", args[1]);
            return;
        }
        long duration = MuteManager.parseDuration(args[2]);
        if (duration < 0) {
            lang.send(sender, "invalid-duration", "value", args[2]);
            return;
        }
        String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                : lang.get("default-mute-reason");
        String name = target.getName() == null ? args[1] : target.getName();
        MuteManager.Mute active = plugin.mutes().mute(target.getUniqueId(), name, duration, reason);
        reason = active.reason();
        String time = active.until() == 0 ? "∞" : MuteManager.format(active.until() - System.currentTimeMillis());
        lang.send(sender, "muted-player", "player", name, "time", time, "reason", reason);
        Player online = target.getPlayer();
        if (online != null) {
            lang.send(online, "you-were-muted", "time", time, "reason", reason);
        }
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer offline(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer p = Bukkit.getOfflinePlayer(name);
        return p.hasPlayedBefore() ? p : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("jchatcontrol.admin")) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBS);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "learn" -> options.addAll(List.of("list", "approve", "deny", "approveall"));
                case "cache" -> options.add("clear");
                case "ai" -> options.addAll(List.of("status", "on", "off"));
                case "mute", "unmute", "violations", "simulate" -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                case "test" -> options.add("-l");
                default -> {
                }
            }
        } else if (args.length == 3) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "block" -> options.addAll(plugin.engine().categories().keySet());
                case "mute" -> options.addAll(List.of("5m", "15m", "1h", "1d", "perm"));
                case "violations" -> options.add("reset");
                case "learn" -> options.addAll(plugin.engine().learning().pending().keySet());
                default -> {
                }
            }
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).sorted().toList();
    }
}
