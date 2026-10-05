package dev.jkaanof.jchatcontrol.bukkit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;

/** /chattest [-l] &lt;message&gt; - quick in-game filter test (permission jchatcontrol.test). */
public final class ChatTestCommand implements TabExecutor {

    private final JccCommand jcc;
    private final JChatControl plugin;

    ChatTestCommand(JChatControl plugin, JccCommand jcc) {
        this.plugin = plugin;
        this.jcc = jcc;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("jchatcontrol.test")) {
            plugin.lang().send(sender, "no-permission");
            return true;
        }
        jcc.testCommand(sender, args, "/" + label);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 && "-l".startsWith(args[0]) ? List.of("-l") : List.of();
    }
}
