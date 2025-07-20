package net.opmasterleo.combat.listener;

import net.opmasterleo.combat.Combat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import java.util.UUID;

public class PlayerDeathListener implements Listener {

    private static final String INTENTIONAL_GAME_DESIGN_KEYWORD = "Intentional Game Design";

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Combat combat = Combat.getInstance();
        Player combatOpponent = combat.getCombatOpponent(victim);
        Player killer = victim.getKiller();
        if (killer == null && combatOpponent != null &&
            event.deathMessage() != null &&
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(event.deathMessage()).contains(INTENTIONAL_GAME_DESIGN_KEYWORD)) {
            victim.setKiller(combatOpponent);
        }

        UUID victimUUID = victim.getUniqueId();
        UUID opponentUUID = combat.getCombatOpponents().get(victimUUID);

        boolean untagOnDeath = combat.getConfig().getBoolean("untag-on-death", true);
        boolean untagOnEnemyDeath = combat.getConfig().getBoolean("untag-on-enemy-death", true);


        if (untagOnDeath) {
            combat.forceCombatCleanup(victimUUID);
            if (combat.getGlowManager() != null) {
                combat.getGlowManager().setGlowing(victim, false);
            }
        }
        if (untagOnEnemyDeath && opponentUUID != null) {
            combat.forceCombatCleanup(opponentUUID);
            Player opponent = combat.getServer().getPlayer(opponentUUID);
            if (opponent != null && opponent.isOnline()) {
                if (combat.getGlowManager() != null) {
                    combat.getGlowManager().setGlowing(opponent, false);
                }

                String noLongerInCombatMsg = combat.getMessage("Messages.NoLongerInCombat");
                if (noLongerInCombatMsg != null && !noLongerInCombatMsg.isEmpty()) {
                    String prefix = combat.getMessage("Messages.Prefix");
                    opponent.sendMessage(prefix + noLongerInCombatMsg);
                }
            }
        }
    }
}