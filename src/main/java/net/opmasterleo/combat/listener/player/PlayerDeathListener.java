package net.opmasterleo.combat.listener.player;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import net.opmasterleo.combat.Combat;

public class PlayerDeathListener implements Listener {

    private static final String INTENTIONAL_GAME_DESIGN_KEYWORD = "Intentional Game Design";
    private static final Combat combat = Combat.getInstance();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        final Player victim = event.getEntity();
        final UUID victimUUID = victim.getUniqueId();
        final Combat.CombatRecord victimRecord = combat.getCombatRecords().get(Combat.uuidToLong(victimUUID));
        final UUID opponentUUID = victimRecord != null ? victimRecord.opponent : null;
        Player killer = victim.getKiller();
        if (killer == null && opponentUUID != null) {
            net.kyori.adventure.text.Component deathComp = event.deathMessage();
            if (deathComp != null) {
                String deathText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(Objects.requireNonNull(deathComp));
                if (deathText.contains(INTENTIONAL_GAME_DESIGN_KEYWORD)) {
                    Player combatOpponent = Bukkit.getPlayer(opponentUUID);
                    if (combatOpponent != null) {
                        victim.setKiller(combatOpponent);
                        killer = combatOpponent;
                    }
                }
            }
        }

        if (combat.getConfig().getBoolean("lightning-on-kill", false) && killer != null && !killer.equals(victim)) {
            Location loc = victim.getLocation();
            World world = loc.getWorld();
            if (world != null) {
                world.strikeLightningEffect(loc);
            }
        }

        final boolean untagOnDeath = combat.getConfig().getBoolean("untag-on-death", true);
        final boolean untagOnEnemyDeath = combat.getConfig().getBoolean("untag-on-enemy-death", true);
        
        if (!untagOnDeath && !untagOnEnemyDeath) {
            return;
        }

        if (untagOnDeath) {
            untagPlayer(victimUUID, victim, false);
        }

        if (untagOnEnemyDeath && opponentUUID != null) {
            Player opponent = Bukkit.getPlayer(opponentUUID);
            untagPlayer(opponentUUID, opponent, true);
        }
    }

    private void untagPlayer(UUID uuid, Player player, boolean sendMessage) {
        combat.getCombatRecords().remove(Combat.uuidToLong(uuid));
        combat.getLastActionBarUpdates().remove(Combat.uuidToLong(uuid));
        if (player != null && combat.getGlowManager() != null) {
            combat.getGlowManager().setGlowing(player, false, null);
        }

        if (sendMessage && player != null && player.isOnline() && combat.isCombatVisible(player)) {
            String msg;
            String type;
            
            if (combat.getConfig().isConfigurationSection("Messages.NoLongerInCombat")) {
                msg = combat.getConfig().getString("Messages.NoLongerInCombat.text", "");
                type = combat.getConfig().getString("Messages.NoLongerInCombat.type", "chat");
            } else {
                msg = combat.getConfig().getString("Messages.NoLongerInCombat", "");
                type = "chat";
            }
            
            if (msg != null && !msg.isEmpty()) {
                String prefix = combat.getPrefix();
                net.kyori.adventure.text.Component component = net.opmasterleo.combat.util.ChatUtil.parse(prefix + msg);
                
                switch (type == null ? "chat" : type.toLowerCase()) {
                    case "actionbar":
                        player.sendActionBar(component);
                        break;
                    case "both":
                        player.sendMessage(component);
                        player.sendActionBar(component);
                        break;
                    case "chat":
                    default:
                        player.sendMessage(component);
                        break;
                }
            }
        }
    }
}
