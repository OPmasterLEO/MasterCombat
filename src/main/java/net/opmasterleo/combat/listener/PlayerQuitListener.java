package net.opmasterleo.combat.listener;

import net.opmasterleo.combat.Combat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerQuitListener implements Listener {

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();
        if (combat.isInCombat(player)) {
            Player opponent = combat.getCombatOpponent(player);

            // Set the opponent as the killer before applying damage
            // This ensures the kill is properly attributed
            if (opponent != null) {
                player.setKiller(opponent);
            }

            player.setHealth(0);
            String logoutMsg = combat.getMessage("Messages.LogoutInCombat");
            if (logoutMsg != null && !logoutMsg.isEmpty()) {
                String message = logoutMsg.replace("%player%", player.getName());
                combat.getServer().getOnlinePlayers().forEach(p -> p.sendMessage(message));
            }

            combat.getCombatPlayers().remove(player.getUniqueId());
            combat.getCombatOpponents().remove(player.getUniqueId());
            if (combat.getGlowManager() != null) {
                combat.getGlowManager().setGlowing(player, false);
                if (opponent != null) {
                    combat.getGlowManager().setGlowing(opponent, false);
                    combat.getCombatPlayers().remove(opponent.getUniqueId());
                    combat.getCombatOpponents().remove(opponent.getUniqueId());
                    opponent.sendMessage(combat.getMessage("Messages.CombatLogged")
                            .replace("%player%", player.getName()));
                }
            }
        }
    }
}