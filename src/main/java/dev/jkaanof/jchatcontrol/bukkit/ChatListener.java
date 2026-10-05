package dev.jkaanof.jchatcontrol.bukkit;

import dev.jkaanof.jchatcontrol.core.FilterEngine;
import dev.jkaanof.jchatcontrol.core.Verdict;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.EventExecutor;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Public chat. Works on Spigot and Paper (Paper bridges AsyncPlayerChatEvent to its own chat event).
 * <ul>
 *     <li>BLOCKING: the async chat thread waits for the AI (max timeout), then the message is sent or blocked.</li>
 *     <li>DELAYED: the message is held back and delivered by the plugin when the AI approves it
 *     (chat thread is never blocked).</li>
 *     <li>POST: the message is delivered at once, the AI checks it afterwards and punishes violations.</li>
 * </ul>
 */
public final class ChatListener implements Listener, EventExecutor {

    public enum Mode { BLOCKING, DELAYED, POST }

    private final JChatControl plugin;

    ChatListener(JChatControl plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Listener listener, Event event) {
        if (event instanceof AsyncPlayerChatEvent chat) {
            onChat(chat);
        }
    }

    private void onChat(AsyncPlayerChatEvent e) {
        JChatControl.Settings s = plugin.settings();
        if (!s.chatEnabled) {
            return;
        }
        Player player = e.getPlayer();
        if (player.hasPermission("jchatcontrol.bypass")) {
            return;
        }
        MuteManager.Mute mute = plugin.mutes().get(player.getUniqueId());
        if (mute != null) {
            e.setCancelled(true);
            plugin.lang().send(player, "muted", "time",
                    mute.until() <= 0 ? "∞" : MuteManager.format(mute.until() - System.currentTimeMillis()),
                    "reason", mute.reason());
            return;
        }

        String message = plugin.preprocess(e.getMessage());
        if (!message.equals(e.getMessage())) {
            e.setMessage(message);
        }
        String plain = ChatColor.stripColor(message);
        boolean aiAllowed = !player.hasPermission("jchatcontrol.bypass.ai");
        FilterEngine.Evaluation ev = plugin.engine().evaluate(plain, player.getUniqueId(), aiAllowed);

        if (ev.isFinal()) {
            apply(e, player, ev, ev.verdict());
            return;
        }

        Mode mode = s.chatMode;
        if (mode == Mode.BLOCKING && !e.isAsynchronous()) {
            mode = Mode.POST; // never block the main thread (chat triggered by a plugin)
        }
        switch (mode) {
            case BLOCKING -> {
                Verdict v;
                try {
                    v = ev.pending().get(s.blockingTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ex) {
                    v = plugin.engine().ai().fallback("timeout");
                    // keep the request running: its answer is still cached / learned / punished
                    ev.pending().thenAccept(late -> {
                        if (late.flagged()) {
                            plugin.moderator().lateViolation(player, late, plain, "chat");
                        }
                    });
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    v = plugin.engine().ai().fallback("interrupted");
                } catch (ExecutionException ex) {
                    v = plugin.engine().ai().fallback("error");
                }
                apply(e, player, ev, v);
            }
            case POST -> ev.pending().thenAccept(v -> {
                if (v.flagged()) {
                    plugin.moderator().lateViolation(player, v, plain, "chat");
                }
            });
            case DELAYED -> hold(e, player, ev);
        }
    }

    private void apply(AsyncPlayerChatEvent e, Player player, FilterEngine.Evaluation ev, Verdict v) {
        Moderator.Decision d = plugin.moderator().decide(player, v, ev.normalized(), "chat");
        if (d.cancel()) {
            e.setCancelled(true);
        } else if (d.replacement() != null) {
            e.setMessage(d.replacement());
        }
    }

    /** DELAYED mode: cancel now, deliver ourselves once the AI answered. */
    private void hold(AsyncPlayerChatEvent e, Player player, FilterEngine.Evaluation ev) {
        String format = e.getFormat();
        String message = e.getMessage();
        List<Player> recipients = new ArrayList<>(e.getRecipients());
        e.setCancelled(true);
        ev.pending().thenAccept(v -> {
            Moderator.Decision d = plugin.moderator().decide(player, v, ev.normalized(), "chat");
            if (d.cancel()) {
                return;
            }
            String text = d.replacement() != null ? d.replacement() : message;
            Bukkit.getScheduler().runTask(plugin, () -> deliver(player, format, text, recipients));
        });
    }

    private void deliver(Player player, String format, String message, List<Player> recipients) {
        if (!player.isOnline()) {
            return;
        }
        String line;
        try {
            line = String.format(Locale.ROOT, format, player.getDisplayName(), message);
        } catch (IllegalFormatException ex) {
            line = "<" + player.getDisplayName() + "> " + message;
        }
        for (Player p : recipients) {
            if (p.isOnline()) {
                p.sendMessage(line);
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }
}
