package com.enhancedechest.gui;

import com.enhancedechest.lang.LanguageManager;
import com.enhancedechest.model.EnderChestData;
import com.enhancedechest.serialization.CodecException;
import com.enhancedechest.serialization.ContainerCodec;
import com.enhancedechest.storage.EnderChestStorage;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.slf4j.Logger;

/**
 * Owns the open and save lifecycle of the custom enderchest GUI.
 *
 * Dupe-safety contract:
 * - open() always loads a fresh snapshot from DB.
 * - If the player already has our GUI open, we close it first (firing InventoryCloseEvent
 *   synchronously, which saves the current state to DB) before loading the fresh snapshot.
 * - save() writes immediately to DB and releases the Inventory object — nothing is cached.
 * - All operations run on the main thread; no async path exists.
 */
@RequiredArgsConstructor
public final class EnderChestService {

    private final LanguageManager lang;
    private final ContainerCodec codec;
    private final EnderChestStorage storage;
    private final Logger logger;

    public void open(Player player) {
        Inventory currentTop = player.getOpenInventory().getTopInventory();
        if (currentTop.getHolder() instanceof EnderChestHolder) {
            player.closeInventory();
        }

        EnderChestData data;
        try {
            data = storage.load(player.getUniqueId());
        } catch (Exception e) {
            logger.error("Failed to load enderchest for {} — aborting open", player.getName(), e);
            player.sendMessage(lang.get("chest.load-failed"));
            return;
        }

        Inventory inv = Bukkit.createInventory(
                new EnderChestHolder(player.getUniqueId()),
                ContainerCodec.CHEST_SIZE,
                lang.getGuiTitle()
        );

        if (data != null && data.containerData() != null && data.containerData().length > 0) {
            try {
                inv.setContents(codec.decode(data.containerData()));
            } catch (CodecException e) {
                logger.error("Codec failure for {} — aborting open to protect stored data", player.getName(), e);
                player.sendMessage(lang.get("chest.codec-failed"));
                return;
            }
        }

        player.openInventory(inv);
    }

    public void save(EnderChestHolder holder, Inventory inventory) {
        byte[] encoded;
        try {
            encoded = codec.encode(inventory.getContents());
        } catch (Exception e) {
            logger.error("Codec encode failure for {} — data NOT saved to prevent corruption",
                    holder.getOwner(), e);
            return;
        }

        try {
            storage.save(holder.getOwner(), encoded);
        } catch (Exception e) {
            logger.error("DB save failure for {}", holder.getOwner(), e);
        }
    }
}
