package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.core.Action;
import dev.jkaanof.jchatcontrol.core.CategorySettings;
import dev.jkaanof.jchatcontrol.core.FilterEngine;
import dev.jkaanof.jchatcontrol.core.SpamGuard;
import dev.jkaanof.jchatcontrol.core.TextNormalizer;
import dev.jkaanof.jchatcontrol.core.Verdict;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Applies verdicts: cancel / censor / warn, notifies staff, logs and adds violation points. */
public final class Moderator {

    /** What to do with the message. */
    public record Decision(boolean cancel, String replacement) {
        static final Decision PASS = new Decision(false, null);
        static final Decision CANCEL = new Decision(true, null);
    }

    private final JChatControl plugin;

    Moderator(JChatControl plugin) {
        this.plugin = plugin;
    }

    /** Decides for a message that has not been delivered yet. Safe to call from any thread. */
    public Decision decide(Player player, Verdict v, TextNormalizer.Normalized n, String context) {
        if (!v.flagged()) {
            return Decision.PASS;
        }
        Lang lang = plugin.lang();
        if (v.source() == Verdict.Source.FALLBACK) {
            // fail-closed mode: AI could not verify the message
            lang.send(player, "unverified");
            return Decision.CANCEL;
        }
        FilterEngine engine = plugin.engine();
        Action action = engine.actionFor(v, Action.BLOCK);
        String category = displayName(v.primaryCategory());
        Decision decision;
        switch (action) {
            case CENSOR -> {
                String censored = engine.censor(n, v.words(), plugin.settings().censorChar);
                if (censored == null) {
                    action = Action.BLOCK;
                    lang.send(player, "blocked", "category", category);
                    decision = Decision.CANCEL;
                } else {
                    lang.send(player, "censored", "category", category);
                    decision = new Decision(false, censored);
                }
            }
            case WARN -> {
                lang.send(player, "warned", "category", category);
                decision = Decision.PASS;
            }
            case LOG -> decision = Decision.PASS;
            default -> {
                lang.send(player, "blocked", "category", category);
                decision = Decision.CANCEL;
            }
        }
        punish(player, v, n.raw(), n, context, action);
        return decision;
    }

    /** A violation found after the message was already delivered (POST mode, AI checked commands). */
    public void lateViolation(Player player, Verdict v, String message, String context) {
        if (!v.flagged() || v.source() == Verdict.Source.FALLBACK) {
            return;
        }
        plugin.lang().send(player, "late-violation", "category", displayName(v.primaryCategory()));
        punish(player, v, message, null, context, Action.LOG);
    }

    private void punish(Player player, Verdict v, String message, TextNormalizer.Normalized n, String context,
                        Action action) {
        FilterEngine engine = plugin.engine();
        int points = engine.pointsFor(v);
        String category = displayName(v.primaryCategory());
        String detail = v.detail() == null ? "" : v.detail();
        String source = v.source().name().toLowerCase(Locale.ROOT);

        plugin.violationLog().log(player.getName(), context, v.primaryCategory(), source, detail, message);

        Bukkit.getScheduler().runTask(plugin, () -> {
            JChatControl.Settings s = plugin.settings();
            if (s.notifyStaff) {
                String alert = plugin.lang().get("staff-alert", "player", player.getName(), "category", category,
                        "message", message, "source", source, "detail", detail, "action",
                        action.name().toLowerCase(Locale.ROOT), "context", context);
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("jchatcontrol.notify")) {
                        staff.sendMessage(alert);
                    }
                }
                if (s.notifyConsole) {
                    Bukkit.getConsoleSender().sendMessage(alert);
                }
            }
            int total = plugin.violations().points(player.getUniqueId());
            List<String> thresholdCommands = List.of();
            if (points > 0 && s.punishmentsEnabled) {
                thresholdCommands = plugin.violations().add(player.getUniqueId(), points);
                total = plugin.violations().points(player.getUniqueId());
            }
            plugin.discord().violation(player.getName(), player.getUniqueId(), v, message, n, context, action, total);
            // 1) commands for this detection source (word list / regex / AI)
            for (String cmd : s.violationCommands.resolve(v)) {
                dispatch(cmd, player.getName(), player.getUniqueId().toString(), v, message, context, total);
            }
            // 2) commands for crossed violation point thresholds
            for (String cmd : thresholdCommands) {
                dispatch(cmd, player.getName(), player.getUniqueId().toString(), v, message, context, total);
            }
        });
    }

    /**
     * Anti-spam / chat delay block. Sends the reason to the player and, when the spam threshold is reached,
     * runs the anti-spam punish commands. Safe to call from any thread.
     */
    public void spamBlocked(Player player, SpamGuard.Result r, String message, String context) {
        plugin.stats().spamBlocked.increment();
        Lang lang = plugin.lang();
        switch (r.type()) {
            case DELAY -> lang.send(player, "spam-delay", "seconds",
                    String.format(Locale.ROOT, "%.1f", Math.max(0.1, r.remainingMs() / 1000.0)));
            case DUPLICATE -> lang.send(player, "spam-duplicate");
            case BURST -> lang.send(player, "spam-burst");
            default -> {
                return;
            }
        }
        if (!r.punish()) {
            return;
        }
        plugin.violationLog().log(player.getName(), context, "spam", r.type().name().toLowerCase(Locale.ROOT), "", message);
        plugin.discord().spam(player.getName(), player.getUniqueId(), r.type(), message, context);
        Bukkit.getScheduler().runTask(plugin, () -> {
            JChatControl.Settings s = plugin.settings();
            if (s.spamNotifyStaff) {
                String alert = lang.get("spam-alert", "player", player.getName(), "context", context);
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("jchatcontrol.notify")) {
                        staff.sendMessage(alert);
                    }
                }
            }
            for (String cmd : s.spamCommands) {
                String c = cmd.replace("{player}", player.getName())
                        .replace("{uuid}", player.getUniqueId().toString())
                        .replace("{type}", r.type().name().toLowerCase(Locale.ROOT))
                        .replace("{context}", context);
                c = c.startsWith("/") ? c.substring(1) : c;
                if (!c.isBlank()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                }
            }
        });
    }

    /**
     * Commands a verdict would run right now for this player (dry run for /jcc test): source commands plus
     * threshold commands that the added points would cross.
     */
    public List<String> plannedCommands(String playerName, UUID uuid, Verdict v, String message, String context) {
        List<String> out = new ArrayList<>();
        if (!v.flagged() || v.source() == Verdict.Source.FALLBACK) {
            return out;
        }
        JChatControl.Settings s = plugin.settings();
        int points = plugin.engine().pointsFor(v);
        int current = uuid == null ? 0 : plugin.violations().points(uuid);
        List<String> threshold = s.punishmentsEnabled && points > 0
                ? plugin.violations().crossed(current, current + points) : List.of();
        int total = s.punishmentsEnabled ? current + points : current;
        String id = uuid == null ? "" : uuid.toString();
        for (String cmd : s.violationCommands.resolve(v)) {
            out.add(fill(cmd, playerName, id, v, message, context, total));
        }
        for (String cmd : threshold) {
            out.add(fill(cmd, playerName, id, v, message, context, total));
        }
        return out;
    }

    private void dispatch(String cmd, String player, String uuid, Verdict v, String message, String context, int points) {
        String c = fill(cmd, player, uuid, v, message, context, points);
        if (!c.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
        }
    }

    /**
     * Placeholders: {player} {uuid} {category} {category_id} {source} {words} {message} {points} {context}
     */
    private String fill(String cmd, String player, String uuid, Verdict v, String message, String context, int points) {
        String sourceKey = ViolationCommands.Kind.of(v.source()) == null ? v.source().name().toLowerCase(Locale.ROOT)
                : ViolationCommands.Kind.of(v.source()).key;
        String c = cmd.replace("{player}", player)
                .replace("{uuid}", uuid)
                .replace("{category}", ChatColor.stripColor(displayName(v.primaryCategory())))
                .replace("{category_id}", v.primaryCategory())
                .replace("{source}", sourceKey)
                .replace("{words}", String.join(", ", v.words()))
                .replace("{message}", message.replace('\n', ' '))
                .replace("{points}", Integer.toString(points))
                .replace("{context}", context);
        return c.startsWith("/") ? c.substring(1) : c;
    }

    public String displayName(String categoryId) {
        CategorySettings cs = plugin.engine().category(categoryId);
        if (cs != null) {
            return Lang.color(cs.displayName());
        }
        if ("unverified".equals(categoryId)) {
            return plugin.lang().get("category-unverified");
        }
        return categoryId;
    }
}
