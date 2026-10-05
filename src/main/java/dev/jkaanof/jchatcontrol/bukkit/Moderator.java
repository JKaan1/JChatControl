package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.core.Action;
import dev.jkaanof.jchatcontrol.core.CategorySettings;
import dev.jkaanof.jchatcontrol.core.FilterEngine;
import dev.jkaanof.jchatcontrol.core.TextNormalizer;
import dev.jkaanof.jchatcontrol.core.Verdict;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

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
        punish(player, v, n.raw(), context, action);
        return decision;
    }

    /** A violation found after the message was already delivered (POST mode, AI checked commands). */
    public void lateViolation(Player player, Verdict v, String message, String context) {
        if (!v.flagged() || v.source() == Verdict.Source.FALLBACK) {
            return;
        }
        plugin.lang().send(player, "late-violation", "category", displayName(v.primaryCategory()));
        punish(player, v, message, context, Action.LOG);
    }

    private void punish(Player player, Verdict v, String message, String context, Action action) {
        FilterEngine engine = plugin.engine();
        int points = engine.pointsFor(v);
        String category = displayName(v.primaryCategory());
        String detail = v.detail() == null ? "" : v.detail();
        String source = v.source().name().toLowerCase(java.util.Locale.ROOT);

        plugin.violationLog().log(player.getName(), context, v.primaryCategory(), source, detail, message);

        Bukkit.getScheduler().runTask(plugin, () -> {
            JChatControl.Settings s = plugin.settings();
            if (s.notifyStaff) {
                String alert = plugin.lang().get("staff-alert", "player", player.getName(), "category", category,
                        "message", message, "source", source, "detail", detail, "action",
                        action.name().toLowerCase(java.util.Locale.ROOT), "context", context);
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("jchatcontrol.notify")) {
                        staff.sendMessage(alert);
                    }
                }
                if (s.notifyConsole) {
                    Bukkit.getConsoleSender().sendMessage(alert);
                }
            }
            if (points > 0 && s.punishmentsEnabled) {
                List<String> commands = plugin.violations().add(player.getUniqueId(), points);
                int total = plugin.violations().points(player.getUniqueId());
                for (String cmd : commands) {
                    String c = cmd.replace("{player}", player.getName())
                            .replace("{uuid}", player.getUniqueId().toString())
                            .replace("{points}", Integer.toString(total))
                            .replace("{category}", category);
                    if (c.startsWith("/")) {
                        c = c.substring(1);
                    }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                }
            }
        });
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
