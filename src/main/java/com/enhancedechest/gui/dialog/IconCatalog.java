package com.enhancedechest.gui.dialog;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catalog of pickable chest icons, backed by the server's {@link Material} registry, plus helpers to
 * render a chosen icon as an Adventure <i>sprite object component</i> — the icon shown <b>inside</b>
 * Dialog action buttons (since {@code 1.21.9} / Adventure 4.25.0, no resource pack required).
 *
 * <p><b>Atlases.</b> A sprite object draws one stitched texture from a named atlas. Vanilla splits
 * these: block textures live in {@code minecraft:blocks} under {@code block/<id>}, item textures in
 * {@code minecraft:items} under {@code item/<id>}. The icon a material shows in the inventory is its
 * item texture when one exists (e.g. doors, boats), otherwise its block texture (e.g. planks). Many
 * derived blocks (slabs, stairs, fences, walls, signs, …) have <i>no</i> flat texture at all — they are
 * drawn from a block model — so they cannot be a sprite and are simply not offered as icons.
 *
 * <p><b>No missing-texture boxes.</b> The exact set of flat textures that exist in the client is
 * bundled at {@code icons/valid-icon-sprites.txt} (generated from the client jar). A material is only
 * catalogued, and a stored icon only rendered, when its resolved sprite is in that set — so the picker
 * never shows a purple/black missing-texture sprite.
 *
 * <p><b>Performance.</b> The valid-sprite set and the full, name-sorted catalog (~2k entries, each with
 * a precomputed display name, lower-cased search name and a reusable sprite {@link Component}) are built
 * <b>once</b>, lazily, and cached immutably. Building any picker page is then just a sub-list — no
 * Material scan and no component allocation per render. Stored-icon sprites are memoized by key too.
 */
public final class IconCatalog {

    private static final String VALID_SPRITES_RESOURCE = "/icons/valid-icon-sprites.txt";
    private static final Key ATLAS_BLOCKS = Key.key("minecraft", "blocks");
    private static final Key ATLAS_ITEMS = Key.key("minecraft", "items");

    /** One pickable icon: a material with its precomputed display name and reusable sprite component. */
    public record Entry(Material material, String key, String displayName, String lowerName, Component sprite) {}

    private static volatile List<Entry> catalog;
    private static volatile Set<String> validSprites;
    private static final Map<String, Component> SPRITE_CACHE = new ConcurrentHashMap<>();

    private IconCatalog() {}

    /** Lazily builds and returns the immutable, name-sorted list of pickable icons. */
    public static List<Entry> all() {
        List<Entry> local = catalog;
        if (local == null) {
            synchronized (IconCatalog.class) {
                local = catalog;
                if (local == null) {
                    local = build();
                    catalog = local;
                }
            }
        }
        return local;
    }

    /** Filters the catalog by a case-insensitive substring of the display name (blank = full list). */
    public static List<Entry> search(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return all();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) {
            if (e.lowerName().contains(q)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Sprite object component for a stored icon key, or null if the key is null/unknown/unrenderable. */
    public static @Nullable Component sprite(@Nullable String materialKey) {
        Material m = material(materialKey);
        if (m == null) {
            return null;
        }
        // computeIfAbsent can't cache nulls; guard the lookup separately so unrenderable keys return null.
        Component cached = SPRITE_CACHE.get(m.getKey().toString());
        if (cached != null) {
            return cached;
        }
        Component built = spriteFor(m);
        if (built != null) {
            SPRITE_CACHE.put(m.getKey().toString(), built);
        }
        return built;
    }

    /** A real item icon for the detail-dialog body, or null if the key is null/unknown/not an item. */
    public static @Nullable ItemStack item(@Nullable String materialKey) {
        Material m = material(materialKey);
        if (m == null || !m.isItem()) {
            return null;
        }
        return ItemStack.of(m);
    }

    /** Resolves a material key string (e.g. {@code minecraft:diamond}) to a Material, or null. */
    public static @Nullable Material material(@Nullable String materialKey) {
        if (materialKey == null || materialKey.isBlank()) {
            return null;
        }
        return Material.matchMaterial(materialKey);
    }

    private static List<Entry> build() {
        List<Entry> list = new ArrayList<>();
        for (Material m : Material.values()) {
            // Only real, current item forms make sensible icons; skip legacy duplicates and AIR.
            if (m.isLegacy() || m == Material.AIR || !m.isItem()) {
                continue;
            }
            Component sprite = spriteFor(m);
            if (sprite == null) {
                continue; // no flat texture exists → would render as a missing-texture box, so omit it.
            }
            String display = displayName(m);
            list.add(new Entry(m, m.getKey().toString(), display, display.toLowerCase(Locale.ROOT), sprite));
        }
        list.sort(Comparator.comparing(Entry::displayName));
        return List.copyOf(list);
    }

    /**
     * Builds the sprite object component for a material, or null if it has no flat texture. Prefers the
     * item texture (the inventory icon for things like doors and boats) over the block texture.
     */
    private static @Nullable Component spriteFor(Material m) {
        NamespacedKey nk = m.getKey();
        String id = nk.getKey();
        Set<String> valid = validSprites();
        if (valid.contains("item/" + id)) {
            return Component.object(ObjectContents.sprite(ATLAS_ITEMS, Key.key(nk.getNamespace(), "item/" + id)));
        }
        if (valid.contains("block/" + id)) {
            return Component.object(ObjectContents.sprite(ATLAS_BLOCKS, Key.key(nk.getNamespace(), "block/" + id)));
        }
        return null;
    }

    /** Lazily loads the bundled set of flat texture names ({@code block/<id>} / {@code item/<id>}). */
    private static Set<String> validSprites() {
        Set<String> local = validSprites;
        if (local == null) {
            synchronized (IconCatalog.class) {
                local = validSprites;
                if (local == null) {
                    local = loadValidSprites();
                    validSprites = local;
                }
            }
        }
        return local;
    }

    private static Set<String> loadValidSprites() {
        Set<String> set = new HashSet<>(4096);
        try (InputStream in = IconCatalog.class.getResourceAsStream(VALID_SPRITES_RESOURCE)) {
            if (in == null) {
                return set; // resource missing → empty catalog rather than a wall of missing textures.
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        set.add(trimmed);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load icon sprite list " + VALID_SPRITES_RESOURCE, e);
        }
        return set;
    }

    /** {@code acacia_boat} -> {@code Acacia Boat}. */
    private static String displayName(Material m) {
        String[] parts = m.getKey().getKey().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
