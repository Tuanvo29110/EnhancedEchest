package com.enhancedechest.command.admin;

import com.enhancedechest.EnhancedEchestPlugin;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;

public final class ReloadCommand {

    private ReloadCommand() {}

    public static int execute(CommandSourceStack source) {
        EnhancedEchestPlugin plugin = (EnhancedEchestPlugin) Bukkit.getPluginManager().getPlugin("EnhancedEchest");
        if (plugin == null || !plugin.isEnabled()) {
            source.getSender().sendMessage(Component.text("[EnhancedEchest] Plugin is not available."));
            return 0;
        }

        plugin.reload();
        source.getSender().sendMessage(plugin.getLanguageManager().get("admin.reload-success"));
        return 1;
    }
}
