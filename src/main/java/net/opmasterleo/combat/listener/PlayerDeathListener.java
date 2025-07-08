package net.opmasterleo.combat.listener;

import net.opmasterleo.combat.Combat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import java.util.UUID;

public class PlayerDeathListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Combat combat = Combat.getInstance();
        
        // Check if we have a combat opponent who should be attributed with this kill
        Player combatOpponent = combat.getCombatOpponent(victim);
        Player killer = victim.getKiller();
        
        // If the kill hasn't been attributed but we have a combat opponent, set them as the killer
        if (killer == null && combatOpponent != null && 
            event.deathMessage() != null && 
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.deathMessage()).contains("Intentional Game Design")) {
            victim.setKiller(combatOpponent);
        }
        
        // Get victim UUID for direct map access
        UUID victimUUID = victim.getUniqueId();
        UUID opponentUUID = null;
        
        // Check config options for removing combat on death
        boolean untagOnDeath = combat.getConfig().getBoolean("untag-on-death", true);
        boolean untagOnEnemyDeath = combat.getConfig().getBoolean("untag-on-enemy-death", true);
        
        // Direct map access to get opponent UUID
        opponentUUID = combat.getCombatOpponents().get(victimUUID);
        
        if (untagOnDeath) {
            // Clean up combat state for the victim
            combat.getCombatPlayers().remove(victimUUID);
            combat.getCombatOpponents().remove(victimUUID);
            
            if (combat.getGlowManager() != null) {
                combat.getGlowManager().setGlowing(victim, false);
            }
        }
        
        // If configured, also remove the opponent from combat when a player dies
        if (untagOnEnemyDeath && opponentUUID != null) {
            combat.getCombatPlayers().remove(opponentUUID);
            combat.getCombatOpponents().remove(opponentUUID);
            
            Player opponent = combat.getServer().getPlayer(opponentUUID);
            if (opponent != null && opponent.isOnline()) {
                if (combat.getGlowManager() != null) {
                    combat.getGlowManager().setGlowing(opponent, false);
                }
                
                // Optional: send message that they're no longer in combat due to opponent death
                String noLongerInCombatMsg = combat.getMessage("Messages.NoLongerInCombat");
                if (noLongerInCombatMsg != null && !noLongerInCombatMsg.isEmpty()) {
                    String prefix = combat.getMessage("Messages.Prefix");
                    opponent.sendMessage(prefix + noLongerInCombatMsg);
                }
            }
        }
        
        // Force run a cleanup to ensure no combat states are left inconsistent
        combat.forceCombatCleanup(victimUUID);
        if (opponentUUID != null) {
            combat.forceCombatCleanup(opponentUUID);
        }
    }
}