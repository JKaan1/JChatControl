package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.core.FilterEngine;
import dev.jkaanof.jchatcontrol.core.TextNormalizer;
import dev.jkaanof.jchatcontrol.core.Verdict;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;

/**
 * Private messages (/msg, /tell, /r ...), signs, books and player name bookkeeping.
 * These events run on the main thread, so only local checks decide instantly; the AI (if enabled)
 * checks afterwards and punishes violations.
 */
public final class ContentListener implements Listener {

    private final JChatControl plugin;

    ContentListener(JChatControl plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        JChatControl.Settings s = plugin.settings();
        if (!s.commandsEnabled) {
            return;
        }
        Player player = e.getPlayer();
        if (player.hasPermission("jchatcontrol.bypass")) {
            return;
        }
        String raw = e.getMessage();
        if (raw.length() < 2) {
            return;
        }
        String[] parts = raw.substring(1).split(" ");
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        Integer skip = s.commands.get(label);
        if (skip == null || parts.length <= skip + 1) {
            return;
        }
        MuteManager.Mute mute = plugin.mutes().get(player.getUniqueId());
        if (mute != null && s.muteBlocksCommands) {
            e.setCancelled(true);
            plugin.lang().send(player, "muted", "time",
                    mute.until() <= 0 ? "∞" : MuteManager.format(mute.until() - System.currentTimeMillis()),
                    "reason", mute.reason());
            return;
        }
        // prefix = "/msg Steve ", text = the rest
        int cut = 1 + parts[0].length();
        for (int i = 1; i <= skip; i++) {
            cut += 1 + parts[i].length();
        }
        if (cut >= raw.length()) {
            return;
        }
        String prefix = raw.substring(0, cut + 1);
        String text = plugin.preprocess(raw.substring(cut + 1));
        String plain = ChatColor.stripColor(text);

        boolean ai = s.commandsUseAi && !player.hasPermission("jchatcontrol.bypass.ai");
        FilterEngine.Evaluation ev = plugin.engine().evaluate(plain, player.getUniqueId(), ai);
        if (ev.isFinal()) {
            Moderator.Decision d = plugin.moderator().decide(player, ev.verdict(), ev.normalized(), "command:" + label);
            if (d.cancel()) {
                e.setCancelled(true);
            } else if (d.replacement() != null) {
                e.setMessage(prefix + d.replacement());
            } else if (!text.equals(raw.substring(cut + 1))) {
                e.setMessage(prefix + text);
            }
            return;
        }
        String finalLabel = label;
        ev.pending().thenAccept(v -> {
            if (v.flagged()) {
                plugin.moderator().lateViolation(player, v, plain, "command:" + finalLabel);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        JChatControl.Settings s = plugin.settings();
        if (!s.signsEnabled || e.getPlayer().hasPermission("jchatcontrol.bypass")) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String line = e.getLine(i);
            if (line != null && !line.isBlank()) {
                sb.append(ChatColor.stripColor(line)).append(' ');
            }
        }
        if (sb.isEmpty()) {
            return;
        }
        if (localViolation(e.getPlayer(), sb.toString().trim(), "sign")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent e) {
        JChatControl.Settings s = plugin.settings();
        if (!s.booksEnabled || e.getPlayer().hasPermission("jchatcontrol.bypass")) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (e.getNewBookMeta().hasTitle()) {
            sb.append(e.getNewBookMeta().getTitle()).append(' ');
        }
        for (String page : e.getNewBookMeta().getPages()) {
            sb.append(ChatColor.stripColor(page)).append(' ');
        }
        if (sb.isEmpty()) {
            return;
        }
        if (localViolation(e.getPlayer(), sb.toString().trim(), "book")) {
            e.setCancelled(true);
        }
    }

    /** Local-only check (word lists + patterns). */
    private boolean localViolation(Player player, String text, String context) {
        TextNormalizer.Normalized n = plugin.engine().normalizer().normalize(text);
        Verdict v = plugin.engine().checkLocal(n);
        if (v == null) {
            return false;
        }
        plugin.engine().stats().record(v);
        Moderator.Decision d = plugin.moderator().decide(player, v, n, context);
        // a sign / book cannot be partially censored reliably: anything but PASS cancels
        return d.cancel() || d.replacement() != null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (plugin.settings().allowPlayerNames) {
            plugin.engine().lists().addRuntimeAllowed(e.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.engine().lists().removeRuntimeAllowed(e.getPlayer().getName());
        if (plugin.engine().ai() != null) {
            plugin.engine().ai().forgetPlayer(e.getPlayer().getUniqueId());
        }
    }

    static Map<String, Integer> defaultCommands() {
        return Map.of("msg", 1, "tell", 1, "w", 1, "whisper", 1, "m", 1, "r", 0, "reply", 0, "me", 0);
    }
}
