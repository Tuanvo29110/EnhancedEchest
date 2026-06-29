package com.enhancedechest.listener;

import com.enhancedechest.service.ChestOpener;
import com.enhancedechest.service.PlayerSettingsCache;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Drives the lifecycle of per-player transient state: preload the settings cache on join, and on quit
 * evict both the settings cache entry and the sort-cooldown timestamp.
 *
 * <p>These handlers are the only lifecycle hooks, and they pair up exactly — every entry added by a join
 * preload (or by a sort) is removed by the matching quit eviction — so both maps stay bounded by the
 * online-player count and never leak. (The join-then-immediate-quit race is handled inside
 * {@link PlayerSettingsCache#preloadSettings}.) Separate from {@link JoinMigrationListener}, which
 * early-returns when migration is off and so must not be relied on to run the preload.
 */
@RequiredArgsConstructor
public final class PlayerSettingsListener implements Listener {

    private final PlayerSettingsCache settings;
    private final ChestOpener chestOpener;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        settings.preloadSettings(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        settings.evictSettings(event.getPlayer().getUniqueId());
        chestOpener.clearSortCooldown(event.getPlayer().getUniqueId());
    }
}
