package net.opmasterleo.combat.listener;

import net.opmasterleo.combat.Combat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class PlayerDeathListener implements Listener {

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Combat combat = Combat.getInstance();
        
        combat.getCombatPlayers().remove(player.getUniqueId());
        Player opponent = combat.getCombatOpponent(player);
        combat.getCombatOpponents().remove(player.getUniqueId());
        
        if (combat.getGlowManager() != null) {
            combat.getGlowManager().setGlowing(player, false);
            if (opponent != null) {
                combat.getGlowManager().setGlowing(opponent, false);
            }
        }
        
        if (opponent != null) {
            combat.getCombatPlayers().remove(opponent.getUniqueId());
            combat.getCombatOpponents().remove(opponent.getUniqueId());
        }
    }
}