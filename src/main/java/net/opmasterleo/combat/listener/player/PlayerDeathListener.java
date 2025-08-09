package net.opmasterleo.combat.listener.player;

import net.opmasterleo.combat.Combat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.Location;
import org.bukkit.World;
import java.util.UUID;

public class PlayerDeathListener implements Listener {

    private static final String INTENTIONAL_GAME_DESIGN_KEYWORD = "Intentional Game Design";

    @EventHandler(priority = EventPriority.NORMAL)
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
        Combat.CombatRecord victimRecord = combat.getCombatRecords().get(victimUUID);
        UUID opponentUUID = victimRecord != null ? victimRecord.opponent : null;
        boolean untagOnDeath = combat.getConfig().getBoolean("untag-on-death", true);
        boolean untagOnEnemyDeath = combat.getConfig().getBoolean("untag-on-enemy-death", true);
        boolean lightningEnabled = combat.getConfig().getBoolean("lightning-on-kill", false);
        if (lightningEnabled && killer != null && !killer.equals(victim)) {
            Location loc = victim.getLocation();
            World world = loc.getWorld();
            if (world != null) {
                world.strikeLightningEffect(loc);
            }
        }

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

                String noLongerInCombatMsg;
                String noLongerInCombatType;
                if (combat.getConfig().isConfigurationSection("Messages.NoLongerInCombat")) {
                    noLongerInCombatMsg = combat.getConfig().getString("Messages.NoLongerInCombat.text", "");
                    noLongerInCombatType = combat.getConfig().getString("Messages.NoLongerInCombat.type", "chat");
                } else {
                    noLongerInCombatMsg = combat.getConfig().getString("Messages.NoLongerInCombat", "");
                    noLongerInCombatType = combat.getConfig().getString("Messages.NoLongerInCombat.type", "chat");
                }
                String prefix = combat.getMessage("Messages.Prefix");
                net.kyori.adventure.text.Component component = net.opmasterleo.combat.util.ChatUtil.parse(prefix + noLongerInCombatMsg);
                switch (noLongerInCombatType == null ? "chat" : noLongerInCombatType.toLowerCase()) {
                    case "actionbar":
                        opponent.sendActionBar(component);
                        break;
                    case "both":
                        opponent.sendMessage(component);
                        opponent.sendActionBar(component);
                        break;
                    case "chat":
                    default:
                        opponent.sendMessage(component);
                        break;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void handle(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();
        
        Combat.CombatRecord record = combat.getCombatRecords().remove(player.getUniqueId());
        
        if (combat.getGlowManager() != null) {
            combat.getGlowManager().setGlowing(player, false);
            if (record != null && record.opponent != null) {
                Player opponent = Bukkit.getPlayer(record.opponent);
                if (opponent != null) {
                    combat.getGlowManager().setGlowing(opponent, false);
                }
            }
        }
        
        if (record != null && record.opponent != null) {
            combat.getCombatRecords().remove(record.opponent);
        }
    }
}