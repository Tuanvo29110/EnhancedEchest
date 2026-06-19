package com.enhancedechest.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class LanguageManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private final JavaPlugin plugin;
    private String locale;
    private FileConfiguration messages;
    private FileConfiguration gui;

    // Hot-path values resolved once per (re)load instead of on every message/open.
    private String cachedPrefix;
    private Component cachedGuiTitle;

    public LanguageManager(JavaPlugin plugin, String locale) {
        this.plugin = plugin;
        this.locale = locale;
        load();
    }

    public void reload(String locale) {
        this.locale = locale;
        load();
    }

    private void load() {
        String base = "language/" + locale + "/";
        if (plugin.getResource(base + "messages.yml") == null) {
            plugin.getSLF4JLogger().warn("Locale '{}' not found, falling back to en_US", locale);
            locale = "en_US";
            base = "language/en_US/";
        }
        saveDefault(base + "messages.yml");
        saveDefault(base + "gui.yml");
        messages = loadFile(base + "messages.yml");
        gui      = loadFile(base + "gui.yml");

        cachedPrefix   = messages.getString("prefix", "[EnhancedEChest] ");
        cachedGuiTitle = parse(gui.getString("enderchest.title", "Ender Chest"));
    }

    private void saveDefault(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
    }

    private FileConfiguration loadFile(String relativePath) {
        File file = new File(plugin.getDataFolder(), relativePath);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        InputStream stream = plugin.getResource(relativePath);
        if (stream != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        return config;
    }

    /**
     * Resolves a message key, substitutes {prefix} and any named {placeholders},
     * then parses the result.
     *
     * Format auto-detection (checked after all substitutions):
     *   - Contains '<'  → MiniMessage
     *   - Otherwise     → legacy '&' codes
     */
    public Component get(String key, String... replacements) {
        String raw = messages.getString(key, key);
        raw = raw.replace("{prefix}", cachedPrefix);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return parse(raw);
    }

    /** Returns the pre-parsed inventory title; recomputed only on reload. */
    public Component getGuiTitle() {
        return cachedGuiTitle;
    }

    private Component parse(String text) {
        if (text.contains("<")) {
            return MINI.deserialize(text);
        }
        return LEGACY.deserialize(text);
    }
}
