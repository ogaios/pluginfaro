package com.scenttracker.listeners;

import com.scenttracker.ScentTrackerPlugin;
import com.scenttracker.managers.ConfigManager;
import com.scenttracker.managers.SniffManager;
import com.scenttracker.managers.TrailManager;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class PlayerListener implements Listener {

    private final ConfigManager config;
    private final TrailManager trailManager;
    private final SniffManager sniffManager;

    public PlayerListener(ConfigManager config, TrailManager trailManager, SniffManager sniffManager) {
        this.config = config;
        this.trailManager = trailManager;
        this.sniffManager = sniffManager;
    }

    /** Evita vazamento de memória: descarta rastro, visualização ativa e referências ao jogador. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        trailManager.remove(id);
        sniffManager.cleanup(id);
    }

    /** Gatilho opcional por clique direito (desligado por padrão em interact.enabled). */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!config.interactEnabled() || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(ScentTrackerPlugin.PERM_USE)) {
            return;
        }

        boolean trigger = switch (config.interactMode()) {
            case ITEM -> {
                ItemStack item = event.getItem();
                yield item != null && item.getType() == config.interactItem();
            }
            case SNEAK_EMPTY_HAND -> {
                if (!player.isSneaking() || !player.getInventory().getItemInMainHand().getType().isAir()) {
                    yield false;
                }
                // não rouba cliques em baús, portas, botões etc.
                Block clicked = event.getClickedBlock();
                yield action == Action.RIGHT_CLICK_BLOCK && clicked != null && !clicked.getType().isInteractable();
            }
        };

        if (trigger) {
            sniffManager.sniff(player, null);
        }
    }
}
