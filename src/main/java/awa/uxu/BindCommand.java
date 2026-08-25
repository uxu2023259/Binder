package awa.uxu;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class BindCommand implements CommandExecutor, TabCompleter {
    private final BindingService service;
    private final BinderGui gui;

    public BindCommand(BinderPlugin plugin, BindingService service, BindingStore store, BinderGui gui) {
        this.service = service;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(service.prefix() + ChatColor.RED + "控制台无法打开 GUI，请在游戏内使用 /bind。 ");
            return true;
        }
        if (args.length > 0) {
            player.sendMessage(service.prefix() + ChatColor.YELLOW + "子命令已停用，所有功能请在 GUI 中操作。 ");
        }
        gui.openMain(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
