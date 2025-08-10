package net.opmasterleo.combat.listener;

import net.kyori.adventure.text.Component;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class CustomDeathMessageListener implements Listener {

    private final Combat plugin;

    public CustomDeathMessageListener() {
        this.plugin = Combat.getInstance();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("General.CustomDeathMessage.enabled", false)) {
            return;
        }

        String prefix = plugin.getConfig().getString("General.CustomDeathMessage.prefix", "");
        if (prefix == null) prefix = "";

        Component originalMessage = event.deathMessage();
        if (originalMessage == null) return;
        String originalText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(originalMessage);
        Component newMessage = ChatUtil.parse(prefix + originalText);

        event.deathMessage(newMessage);
    }
}