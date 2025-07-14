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
        if (combat.getWorldGuardUtil() != null && combat.getWorldGuardUtil().isPvpDenied(player)) {
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

        if (event.getDamager() instanceof Projectile projectile) {
            if (isIgnoredProjectile(combat, projectile)) {
                return;
            }
            
            if (projectile.getShooter() instanceof Player shooter && shooter.getUniqueId().equals(player.getUniqueId())) {
                if (combat.getConfig().getBoolean("self-combat", false) && event.getFinalDamage() > 0) {
                    combat.setCombat(player, player);
                }
                return;
            }
        }

        if (event.getDamager() instanceof Player damager && damager.getUniqueId().equals(player.getUniqueId())) {
            if (combat.getConfig().getBoolean("self-combat", false) && event.getFinalDamage() > 0) {
                combat.setCombat(player, player);
            }
            return;
        }

        if (Combat.getInstance().getConfig().getBoolean("link-tnt", true)) {
            Entity damager = event.getDamager();
            String entityTypeName = damager.getType().name();

            if (entityTypeName.equals("PRIMED_TNT") || entityTypeName.equals("MINECART_TNT")) {
                Player placer = Combat.getInstance().getCrystalManager().getPlacer(damager);

                if (placer != null && placer.getUniqueId().equals(player.getUniqueId())) {
                    if (Combat.getInstance().getConfig().getBoolean("self-combat", false)) {
                        Combat.getInstance().setCombat(player, player);
                    }
                    return;
                }
            }
        }

        if (Combat.getInstance().getConfig().getBoolean("link-respawn-anchor", true)) {
            Entity damager = event.getDamager();
            if (damager instanceof TNTPrimed tnt && tnt.hasMetadata("respawn_anchor_explosion")) {
                Object activatorObj = tnt.getMetadata("respawn_anchor_activator").get(0).value();
                if (activatorObj instanceof Player activator && activator.getUniqueId().equals(player.getUniqueId())) {
                    if (Combat.getInstance().getConfig().getBoolean("self-combat", false)) {
                        Combat.getInstance().setCombat(player, player);
                    }
                    return;
                }
            }
        }

        if (Combat.getInstance().getConfig().getBoolean("link-fishing-rod", true) && event.getDamager() instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player shooter && shooter.getUniqueId().equals(player.getUniqueId())) {
                if (Combat.getInstance().getConfig().getBoolean("self-combat", false)) {
                    Combat.getInstance().setCombat(player, player);
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