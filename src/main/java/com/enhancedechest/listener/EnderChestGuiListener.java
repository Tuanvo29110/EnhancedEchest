package com.enhancedechest.listener;

import com.enhancedechest.config.PluginConfig;
import com.enhancedechest.gui.EnderChestAnimator;
import com.enhancedechest.gui.EnderChestHolder;
import com.enhancedechest.gui.EnderChestService;
import com.enhancedechest.lang.LanguageManager;
import com.enhancedechest.model.ChestKind;
import com.tcoded.folialib.FoliaLib;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public final class EnderChestGuiListener implements Listener {

    /** Minimum gap between deny sounds per player, so spam-clicking can't machine-gun the sound. */
    private static final long DENY_SOUND_COOLDOWN_MILLIS = 350L;

    private final EnderChestService service;
    private final FoliaLib foliaLib;
    private final LanguageManager lang;
    private final PluginConfig config;

    /** Last time (ms) each player heard the deny sound; cleared on chest close. */
    private final Map<UUID, Long> lastDenySoundAt = new ConcurrentHashMap<>();

    /**
     * Temporary chests are take-only: items may be removed but never added. Cancels any click that
     * would deposit into the temp (top) inventory — placing from the cursor, swapping, hotbar swaps,
     * and shift-clicking from the player inventory — while leaving pickups and shift-clicks out of the
     * temp chest untouched.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EnderChestHolder holder)) return;
        if (holder.getKind() != ChestKind.TEMP) return;

        Inventory top = event.getView().getTopInventory();
        boolean deposit = isDeposit(event, top);
        if (deposit) {
            event.setCancelled(true);
            notifyTakeOnly(event.getWhoClicked());
        }
    }

    private static boolean isDeposit(InventoryClickEvent event, Inventory top) {
        Inventory clicked = event.getClickedInventory();
        return switch (event.getAction()) {
            // Cursor → a clicked top slot.
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR,
                 HOTBAR_SWAP -> clicked != null && clicked.equals(top);
            // Shift-click from the player inventory moves the stack into the temp chest.
            case MOVE_TO_OTHER_INVENTORY -> clicked != null && !clicked.equals(top);
            default -> false;
        };
    }

    /** Cancels any drag that would spread items into the temp (top) inventory slots. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EnderChestHolder holder)) return;
        if (holder.getKind() != ChestKind.TEMP) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) { // a raw slot below topSize belongs to the top inventory
                event.setCancelled(true);
                notifyTakeOnly(event.getWhoClicked());
                return;
            }
        }
    }

    private void notifyTakeOnly(org.bukkit.entity.HumanEntity who) {
        if (who instanceof Player p) {
            p.sendActionBar(lang.get("chest.temp-take-only"));
            Sound sound = config.getTempDenySound();
            if (sound != null && notOnSoundCooldown(p)) {
                p.playSound(sound);
            }
        }
    }

    /** True if enough time has passed since this player last heard the deny sound (and records now). */
    private boolean notOnSoundCooldown(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastDenySoundAt.get(p.getUniqueId());
        if (last != null && now - last < DENY_SOUND_COOLDOWN_MILLIS) {
            return false;
        }
        lastDenySoundAt.put(p.getUniqueId(), now);
        return true;
    }

    /**
     * Saves inventory contents to DB on every close, regardless of close reason.
     * This fires for normal closes, /ec reopens (reason OPEN_NEW), and forced closes
     * from server-side events. The DB write is always correct because:
     * - On reopen via /ec: save fires here first, then EnderChestService.open() waits
     *   for the pending save before loading the fresh snapshot. No stale state.
     * - On quit: PlayerQuitListener fires save independently; both are idempotent.
     *
     * Close animation: dispatched to the block's region thread via runAtLocation so
     * the NMS call is always on the correct thread (required for Folia).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof EnderChestHolder ecHolder)) return;

        lastDenySoundAt.remove(event.getPlayer().getUniqueId());

        service.save(ecHolder, top);

        Location sourceBlock = ecHolder.getSourceBlock();
        if (sourceBlock != null) {
            Player player = (Player) event.getPlayer();
            foliaLib.getScheduler().runAtLocation(sourceBlock, task ->
                    EnderChestAnimator.close(player, sourceBlock));
        }
    }
}
