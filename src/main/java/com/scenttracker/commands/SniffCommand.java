package com.scenttracker.commands;

import com.scenttracker.ScentTrackerPlugin;
import com.scenttracker.managers.ConfigManager;
import com.scenttracker.managers.SniffManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /farejar [jogador|reload]  (alias: /sniff)
 */
public final class SniffCommand implements TabExecutor {

    private final ScentTrackerPlugin plugin;
    private final ConfigManager config;
    private final SniffManager sniffManager;

    public SniffCommand(ScentTrackerPlugin plugin, ConfigManager config, SniffManager sniffManager) {
        this.plugin = plugin;
        this.config = config;
        this.sniffManager = sniffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(ScentTrackerPlugin.PERM_RELOAD)) {
                sender.sendMessage(config.prefixed("no-permission"));
                return true;
            }
            plugin.reloadAll();
            sender.sendMessage(config.prefixed("reload-success"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(config.prefixed("players-only"));
            return true;
        }
        if (!player.hasPermission(ScentTrackerPlugin.PERM_USE)) {
            player.sendMessage(config.prefixed("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sniffManager.sniff(player, null);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            player.sendMessage(config.prefixed("player-not-found", Placeholder.unparsed("target", args[0])));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(config.prefixed("target-self"));
            return true;
        }

        sniffManager.sniff(player, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();

        if (sender.hasPermission(ScentTrackerPlugin.PERM_RELOAD) && "reload".startsWith(prefix)) {
            out.add("reload");
        }
        if (sender instanceof Player self && self.hasPermission(ScentTrackerPlugin.PERM_USE)) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.equals(self) || !self.canSee(online)) {
                    continue;
                }
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(online.getName());
                }
            }
        }
        return out;
    }
}
