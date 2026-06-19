package com.enhancedechest.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Generic YAML migrator that runs on startup for every config/language file.
 * <p>
 * Three things it does (in order):
 * <ol>
 *   <li>Apply key renames — transfers the user's old value to the new key name.</li>
 *   <li>Add missing keys — fills in any key absent from the user's file with the bundled default.</li>
 *   <li>Write to disk — only when at least one change was made.</li>
 * </ol>
 * <p>
 * User values for existing, unchanged keys are NEVER touched.
 */
public final class YamlMigrator {

    /** A key rename for one specific version upgrade. */
    public record Rename(String oldPath, String newPath) {}

    private YamlMigrator() {}

    /**
     * Migrates {@code file} against {@code defaultStream}.
     *
     * @param file          the on-disk YAML file to upgrade
     * @param defaultStream the bundled resource stream with canonical default keys/values
     * @param renames       ordered list of key renames (applied before filling missing keys)
     * @param log           logger for summary output
     * @return {@code true} if the file was modified and saved
     */
    public static boolean migrate(File file, InputStream defaultStream,
                                  List<Rename> renames, Logger log) {
        if (defaultStream == null) return false;

        YamlConfiguration user;
        try {
            user = YamlConfiguration.loadConfiguration(file);
        } catch (Exception e) {
            log.error("Could not read {} for migration: {}", file.getName(), e.getMessage());
            return false;
        }

        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(defaultStream, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            log.error("Could not read default resource for {}: {}", file.getName(), e.getMessage());
            return false;
        }

        int renamed = applyRenames(user, renames, log);
        int added   = addMissingKeys(user, defaults);

        if (renamed == 0 && added == 0) return false;

        try {
            user.save(file);
        } catch (IOException e) {
            log.error("Failed to save migrated {}: {}", file.getName(), e.getMessage());
            return false;
        }

        StringBuilder summary = new StringBuilder("Migrated ").append(file.getName()).append(": ");
        if (added   > 0) summary.append("added ").append(added).append(" key(s)");
        if (renamed > 0) {
            if (added > 0) summary.append(", ");
            summary.append("renamed ").append(renamed).append(" key(s)");
        }
        log.info(summary.toString());
        return true;
    }

    // Returns the number of renames applied.
    private static int applyRenames(YamlConfiguration user, List<Rename> renames, Logger log) {
        int count = 0;
        for (Rename r : renames) {
            if (!user.contains(r.oldPath())) continue;
            // Copy value to new key only if the new key isn't already set by the user.
            if (!user.contains(r.newPath())) {
                user.set(r.newPath(), user.get(r.oldPath()));
            }
            user.set(r.oldPath(), null);
            log.info("  '{}' → '{}' (key renamed in new version)", r.oldPath(), r.newPath());
            count++;
        }
        return count;
    }

    // Returns the number of keys added.
    private static int addMissingKeys(YamlConfiguration user, YamlConfiguration defaults) {
        int count = 0;
        for (String path : defaults.getKeys(true)) {
            // Skip intermediate sections — setting a leaf key auto-creates its parent sections.
            if (defaults.isConfigurationSection(path)) continue;
            if (!user.contains(path)) {
                user.set(path, defaults.get(path));
                count++;
            }
        }
        return count;
    }
}
