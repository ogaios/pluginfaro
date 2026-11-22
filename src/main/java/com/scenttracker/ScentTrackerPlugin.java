package com.scenttracker;

import com.scenttracker.commands.SniffCommand;
import com.scenttracker.listeners.PlayerListener;
import com.scenttracker.managers.ConfigManager;
import com.scenttracker.managers.SniffManager;
import com.scenttracker.managers.TrailManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class ScentTrackerPlugin extends JavaPlugin {

    public static final String PERM_USE = "scenttracker.use";
    public static final String PERM_BYPASS = "scenttracker.bypass";
    public static final String PERM_RELOAD = "scenttracker.reload";
    public static final String PERM_COOLDOWN_BYPASS = "scenttracker.cooldown.bypass";

    private ConfigManager configManager;
    private TrailManager trailManager;
    private SniffManager sniffManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        configManager = new ConfigManager(this);
        configManager.load();
        applyUsePermissionDefault();

        trailManager = new TrailManager(this, configManager);
        sniffManager = new SniffManager(this, configManager, trailManager);

        PluginCommand command = Objects.requireNonNull(getCommand("farejar"), "comando 'farejar' ausente no plugin.yml");
        SniffCommand handler = new SniffCommand(this, configManager, sniffManager);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        getServer().getPluginManager().registerEvents(
                new PlayerListener(configManager, trailManager, sniffManager), this);

        trailManager.start();
        sniffManager.start();
    }

    @Override
    public void onDisable() {
        if (sniffManager != null) {
            sniffManager.shutdown();
        }
        if (trailManager != null) {
            trailManager.stop();
            trailManager.clear();
        }
    }

    /** Recarrega o config.yml e reinicia a gravação com o novo intervalo. */
    public void reloadAll() {
        configManager.load();
        applyUsePermissionDefault();
        trailManager.start();
    }

    /** Aplica o padrão de scenttracker.use definido em permissions.use-default. */
    private void applyUsePermissionDefault() {
        String raw = getConfig().getString("permissions.use-default", "TRUE");
        PermissionDefault value = PermissionDefault.getByName(raw);
        if (value == null) {
            getLogger().warning("permissions.use-default inválido ('" + raw + "'); usando TRUE.");
            value = PermissionDefault.TRUE;
        }
        Permission permission = getServer().getPluginManager().getPermission(PERM_USE);
        if (permission != null) {
            permission.setDefault(value);
            getServer().getPluginManager().recalculatePermissionDefaults(permission);
            for (Player online : getServer().getOnlinePlayers()) {
                online.recalculatePermissions();
            }
        }
    }
}
