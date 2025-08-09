package net.opmasterleo.combat.listener;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;

public class CustomDeathMessageListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Combat combat = Combat.getInstance();
        FileConfiguration config = combat.getConfig();
        if (!config.getBoolean("General.CustomDeathMessage.enabled", false)) {
            return;
        }
        
        String prefixRaw = config.getString("General.CustomDeathMessage.prefix", "");
        if (prefixRaw == null) return;
        
        Component prefix = ChatUtil.parse(prefixRaw);
        Component vanillaMessage = null;
        try {
            vanillaMessage = event.deathMessage();
        } catch (Exception e) {
            combat.getLogger().warning("Error getting death message: " + e.getMessage());
        }
        
        if (vanillaMessage == null) {
            vanillaMessage = Component.text(event.getEntity().getName() + " died");
        }

        TextColor lastColor = ChatUtil.getLastColor(prefix);
        if (lastColor != null) {
            vanillaMessage = vanillaMessage.colorIfAbsent(lastColor);
        }

        Component finalMessage = prefix.append(vanillaMessage);
        try {
            event.deathMessage(finalMessage);
        } catch (Exception e1) {
            try {
                String legacyMessage = ChatUtil.legacy(finalMessage);
                try {
                    event.deathMessage(Component.text(legacyMessage));
                } catch (Exception e2) {
                    combat.getLogger().warning("Failed to set death message using Component API: " + e2.getMessage());
                }
            } catch (Exception e2) {
                combat.getLogger().warning("Error setting death message: " + e2.getMessage());
            }
        }
    }
}