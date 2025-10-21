package net.opmasterleo.combat.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.EntityType;

import java.util.Set;
import net.opmasterleo.combat.Combat;

public class SelfCombatListener implements Listener {

    @EventHandler
    public void handle(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFinalDamage() <= 0) return;

        Combat combat = Combat.getInstance();
        boolean selfCombatEnabled = combat.getConfig().getBoolean("self-combat", false);
        if (combat.getWorldGuardUtil() != null && combat.getWorldGuardUtil().isPvpDenied(player.getLocation())) {
            return;
        }

        if (event.getDamager() instanceof Projectile projectile && 
            projectile.getType() == EntityType.ENDER_PEARL) {
            return;
        }

        if (player.getHealth() <= event.getFinalDamage()) {
            Player opponent = combat.getCombatOpponent(player);
            if (opponent != null && !opponent.equals(player)) {
                player.setKiller(opponent);
            }
        }

        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile) {
            if (isIgnoredProjectile(combat, projectile)) {
                return;
            }
            
            if (projectile.getShooter() instanceof Player shooter && shooter.getUniqueId().equals(player.getUniqueId())) {
                if (selfCombatEnabled) {
                    combat.setCombat(player, player);
                }
                return;
            }
        }

        if (damager instanceof Player damagerPlayer && damagerPlayer.getUniqueId().equals(player.getUniqueId())) {
            if (selfCombatEnabled) {
                combat.setCombat(player, player);
            }
            return;
        }

        if (combat.getConfig().getBoolean("link-tnt", true)) {
            String entityTypeName = damager.getType().name();

            if (entityTypeName.equals("PRIMED_TNT") || entityTypeName.equals("MINECART_TNT")) {
                Player placer = combat.getCrystalManager().getPlacer(damager);

                if (placer != null && placer.getUniqueId().equals(player.getUniqueId())) {
                    if (selfCombatEnabled) {
                        combat.setCombat(player, player);
                    }
                    return;
                }
            }
        }

        if (combat.getConfig().getBoolean("link-respawn-anchor", true)) {
            if (damager instanceof TNTPrimed tnt && tnt.hasMetadata("respawn_anchor_explosion")) {
                Object activatorObj = tnt.getMetadata("respawn_anchor_activator").get(0).value();
                if (activatorObj instanceof Player activator && activator.getUniqueId().equals(player.getUniqueId())) {
                    if (selfCombatEnabled) {
                        combat.setCombat(player, player);
                    }
                    return;
                }
            }
        }

        if (combat.getConfig().getBoolean("link-fishing-rod", true) && damager instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player shooter && shooter.getUniqueId().equals(player.getUniqueId())) {
                if (selfCombatEnabled) {
                    combat.setCombat(player, player);
                }
            }
        }
    }

    private boolean isIgnoredProjectile(Combat combat, Projectile projectile) {
        if (projectile.getType() == EntityType.ENDER_PEARL) {
            return true;
        }
        
        String projType = projectile.getType().name().toUpperCase();
        Set<String> ignoredProjectiles = combat.getIgnoredProjectiles();
        return ignoredProjectiles != null && ignoredProjectiles.contains(projType);
    }
}