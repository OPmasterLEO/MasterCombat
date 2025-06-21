package net.opmasterleo.combat.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import net.opmasterleo.combat.Combat;
import net.kyori.adventure.text.Component;

public class PlayerQuitListener implements Listener {
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();
        if (combat.isInCombat(player)) {
            Player opponent = combat.getCombatOpponent(player);
            if (opponent != null && opponent.isOnline() && !opponent.equals(player)) {
                player.damage(1000.0, opponent);
            } else {
                player.damage(1000.0);
            }
        }

        if (Combat.getInstance().isInCombat(player)) {
            if (player.getHealth() > 0.0) {
                player.setHealth(0.0);
            }
            String logoutMsg = Combat.getInstance().getMessage("Messages.LogoutInCombat");
            if (logoutMsg != null && !logoutMsg.isEmpty()) {
                Bukkit.getServer().sendMessage(Component.text(Combat.getInstance().getMessage("Messages.Prefix") + logoutMsg.replace("%player%", player.getName())));
            }
        }

        Combat.getInstance().getCombatPlayers().remove(player.getUniqueId());
    }
}