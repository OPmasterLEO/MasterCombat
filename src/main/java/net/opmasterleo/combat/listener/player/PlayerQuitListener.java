package net.opmasterleo.combat.listener.player;

import net.kyori.adventure.text.Component;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerQuitListener implements Listener {

    private final Combat plugin;

    public PlayerQuitListener() {
        this.plugin = Combat.getInstance();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isInCombat(player)) return;

        UUID playerUUID = player.getUniqueId();
        Combat.CombatRecord record = plugin.getCombatRecords().get(playerUUID);
        Player opponent = null;
        if (record != null && record.opponent != null) {
            opponent = Bukkit.getPlayer(record.opponent);
            if (opponent != null) {
                player.setKiller(opponent);
            }
        }

        try {
            player.setHealth(0);
        } catch (Exception ignored) {
        }

        if (opponent != null) {
            String logoutMessage = plugin.getConfig().getString("Messages.CombatLogged.text", "");
            if (logoutMessage != null && !logoutMessage.isEmpty()) {
                logoutMessage = logoutMessage.replace("%player%", player.getName());
                Component message = ChatUtil.parse(plugin.getPrefix() + logoutMessage);
                opponent.sendMessage(message);
            }
        }

        plugin.getCombatRecords().remove(playerUUID);
        if (plugin.getGlowManager() != null) {
            plugin.getGlowManager().untrackPlayer(player);
        }
        plugin.forceCombatCleanup(playerUUID);
    }
}