package net.opmasterleo.combat.listener;

import java.util.Set;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import net.opmasterleo.combat.Combat;

public final class EntityDamageByEntityListener implements Listener {

    @EventHandler
    public void handle(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        Combat combat = Combat.getInstance();
        if (combat.getWorldGuardUtil() != null && combat.getWorldGuardUtil().isPvpDenied(player)) return;

        Entity damager = event.getDamager();
        boolean selfCombat = combat.getConfig().getBoolean("self-combat", false);
        boolean linkProjectiles = combat.getConfig().getBoolean("link-projectiles", true);
        boolean linkEndCrystals = combat.getConfig().getBoolean("link-end-crystals", true);
        boolean linkPets = combat.getConfig().getBoolean("link-pets", true);
        boolean linkFishingRod = combat.getConfig().getBoolean("link-fishing-rod", true);
        boolean linkTnt = combat.getConfig().getBoolean("link-tnt", true);
        Set<String> ignoredProjectiles = combat.getIgnoredProjectiles();

        if (damager instanceof Player damagerP) {
            if (damagerP.getUniqueId().equals(player.getUniqueId())) {
                if (selfCombat) {
                    if (!combat.isInCombat(player))
                        combat.setCombat(player, player);
                }
                return;
            }
            if (!combat.isInCombat(player) || combat.getCombatOpponent(player) == null || !combat.getCombatOpponent(player).getUniqueId().equals(damagerP.getUniqueId())) {
                combat.setCombat(player, damagerP);
                combat.setCombat(damagerP, player);
            }
        }

        if (damager instanceof Projectile projectile && linkProjectiles) {
            if (projectile.getShooter() instanceof Player shooter) {
                if (shooter.getUniqueId().equals(player.getUniqueId())) {
                    if (selfCombat && !combat.isInCombat(player)) {
                        combat.setCombat(player, player);
                    }
                } else {
                    if (!ignoredProjectiles.contains(projectile.getType().name().toUpperCase())) {
                        if (!combat.isInCombat(player) || combat.getCombatOpponent(player) == null || !combat.getCombatOpponent(player).getUniqueId().equals(shooter.getUniqueId())) {
                            combat.setCombat(player, shooter);
                            combat.setCombat(shooter, player);
                        }
                    }
                }
            }
        }

        if (linkEndCrystals && damager.getType() == EntityType.END_CRYSTAL) {
            Player placer = combat.getCrystalManager().getPlacer(damager);
            if (placer != null) {
                if (placer.getUniqueId().equals(player.getUniqueId()) && !selfCombat) {
                    return;
                }
                if (!combat.isInCombat(player) || combat.getCombatOpponent(player) == null || !combat.getCombatOpponent(player).getUniqueId().equals(placer.getUniqueId())) {
                    combat.setCombat(player, placer);
                    combat.setCombat(placer, player);
                }
            }
        }

        if (linkPets && damager instanceof Tameable tameable) {
            if (tameable.getOwner() instanceof Player owner) {
                if (owner.getUniqueId().equals(player.getUniqueId())) return;
                if (!combat.isInCombat(player) || combat.getCombatOpponent(player) == null || !combat.getCombatOpponent(player).getUniqueId().equals(owner.getUniqueId())) {
                    combat.setCombat(player, owner);
                    combat.setCombat(owner, player);
                }
            }
        }

        if (linkFishingRod && damager instanceof FishHook fishHook) {
            if (fishHook.getShooter() instanceof Player shooter) {
                if (shooter.getUniqueId().equals(player.getUniqueId())) return;
                if (!combat.isInCombat(player) || combat.getCombatOpponent(player) == null || !combat.getCombatOpponent(player).getUniqueId().equals(shooter.getUniqueId())) {
                    combat.setCombat(player, shooter);
                    combat.setCombat(shooter, player);
                }
            }
        }

        if (linkTnt && damager instanceof TNTPrimed tnt) {
            if (tnt.getSource() instanceof Player source) {
                if (source.getUniqueId().equals(player.getUniqueId())) return;
                if (!combat.isInCombat(player) || combat.getCombatOpponent(player) == null || !combat.getCombatOpponent(player).getUniqueId().equals(source.getUniqueId())) {
                    combat.setCombat(player, source);
                    combat.setCombat(source, player);
                }
            }
        }
    }
}
