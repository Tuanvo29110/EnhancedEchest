package com.enhancedechest.listener;

import com.enhancedechest.gui.EnderChestAnimator;
import com.enhancedechest.gui.EnderChestService;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

@RequiredArgsConstructor
public final class VanillaEnderChestListener implements Listener {

    private final EnderChestService service;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        var block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ENDER_CHEST) return;

        event.setCancelled(true);

        // Right-clicking an ender chest block always opens the GUI — no permission required.
        // The open-by-command permission only gates /enderchest and /eclist.
        Player player = event.getPlayer();
        Location blockLoc = block.getLocation();

        // We are on the block's region thread (Folia) or main thread (Spigot/Paper),
        // which is the correct thread to call startOpen on the block entity.
        EnderChestAnimator.open(player, blockLoc);

        service.open(player, blockLoc);
    }
}
