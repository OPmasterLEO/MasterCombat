package net.opmasterleo.combat.listener;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.GameMode;

public class DirectCombatListener extends Combat.PacketListenerAdapter implements Listener {

    public DirectCombatListener() {
        Combat combat = Combat.getInstance();
        if (combat != null && combat.isPacketEventsAvailable()) {
            combat.safelyRegisterPacketListener(this);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        
        try {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
            
            Player attacker = (Player) event.getPlayer();
            Combat combat = Combat.getInstance();
            Entity target = combat.getEntityManager().getEntity(packet.getEntityId());
            
            if (!(target instanceof Player victim)) return;
            if (attacker.isDead() || victim.isDead()) return;
            
            SchedulerUtil.runTask(combat, () -> {
                handleCombat(attacker, victim);
            });
        } catch (Exception e) {
            Combat.getInstance().debug("Error processing packet combat: " + e.getMessage());
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Combat combat = Combat.getInstance();
        double minDamage = combat.getConfig().getDouble("damage-threshold", 0.01);
        if (event.getFinalDamage() <= minDamage) {
            combat.debug("Skipped damage event: damage below threshold (" + event.getFinalDamage() + " <= " + minDamage + ")");
            return;
        }
        
        if (!(event.getEntity() instanceof Player victim)) return;
        
        Player attacker = null;
        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            attacker = (Player) damager;
        }
        
        else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player) {
            attacker = (Player) projectile.getShooter();
            String projectileType = damager.getType().name().toUpperCase();
            if (combat.getIgnoredProjectiles().contains(projectileType)) {
                combat.debug("Skipping combat - projectile type is ignored: " + projectileType);
                return;
            }
        }
        
        if (attacker == null) return;
        if (victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks() / 2) {
            combat.debug("Skipping combat - victim has damage immunity: " + victim.getName() + 
                       " (Ticks: " + victim.getNoDamageTicks() + "/" + victim.getMaximumNoDamageTicks() + ")");
            return;
        }
        
        if (!combat.isCombatEnabledInWorld(victim) || !combat.isCombatEnabledInWorld(attacker)) {
            combat.debug("Skipping combat - world disabled: " + victim.getWorld().getName());
            return;
        }
        
        combat.debug("Direct combat detected: " + attacker.getName() + " -> " + victim.getName());
        handleCombat(attacker, victim);
    }
 
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player shooter)) return;
        
        Combat combat = Combat.getInstance();
        if (!combat.isCombatEnabled() || !combat.isCombatEnabledInWorld(shooter)) return;
        
        Projectile projectile = event.getEntity();
        String projectileType = projectile.getType().name().toUpperCase();
        
        if (combat.getIgnoredProjectiles().contains(projectileType)) {
            combat.debug("Ignoring projectile: " + projectileType);
            return;
        }
        
        combat.debug("Projectile launched by " + shooter.getName() + ": " + projectileType);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getHitEntity() instanceof Player victim)) return;
        if (!(event.getEntity().getShooter() instanceof Player attacker)) return;
        
        Combat combat = Combat.getInstance();
        String projectileType = event.getEntity().getType().name().toUpperCase();
        
        if (combat.getIgnoredProjectiles().contains(projectileType)) {
            combat.debug("Ignoring projectile hit: " + projectileType);
            return;
        }
        
        combat.debug("Projectile hit detected: " + attacker.getName() + " -> " + victim.getName());
        handleCombat(attacker, victim);
    }
    
    private void handleCombat(Player attacker, Player victim) {
        Combat combat = Combat.getInstance();
        if (attacker == null || victim == null) return;
        if (attacker.isDead() || victim.isDead()) return;
        if (victim.getUniqueId().equals(attacker.getUniqueId())) {
            if (combat.getConfig().getBoolean("self-combat", false)) {
                combat.debug("Self combat enabled - tagging player");
                combat.directSetCombat(attacker, attacker);
            } else {
                combat.debug("Self combat disabled");
            }
            return;
        }

        if (attacker.getGameMode() == GameMode.CREATIVE || 
            attacker.getGameMode() == GameMode.SPECTATOR || 
            victim.getGameMode() == GameMode.CREATIVE || 
            victim.getGameMode() == GameMode.SPECTATOR) {
            combat.debug("Skipping combat - creative/spectator mode");
            return;
        }

        if (combat.getSuperVanishManager() != null && 
            (combat.getSuperVanishManager().isVanished(attacker) || 
             combat.getSuperVanishManager().isVanished(victim))) {
            combat.debug("Skipping combat - player vanished");
            return;
        }
        
        if (combat.getWorldGuardUtil() != null && 
            (combat.getWorldGuardUtil().isPvpDenied(attacker.getLocation()) || 
             combat.getWorldGuardUtil().isPvpDenied(victim.getLocation()))) {
            combat.debug("Skipping combat - WorldGuard PvP denied");
            return;
        }
        
        if (combat.getNewbieProtectionListener() != null) {
            boolean attackerProtected = combat.getNewbieProtectionListener().isActuallyProtected(attacker);
            boolean victimProtected = combat.getNewbieProtectionListener().isActuallyProtected(victim);
            
            if (attackerProtected && !victimProtected) {
                combat.getNewbieProtectionListener().sendBlockedMessage(
                    attacker, 
                    combat.getNewbieProtectionListener().getCrystalBlockMessage()
                );
                combat.debug("Skipping combat - attacker protected");
                return;
            }
            
            if (!attackerProtected && victimProtected) {
                combat.getNewbieProtectionListener().sendBlockedMessage(
                    attacker, 
                    combat.getNewbieProtectionListener().getAttackerMessage()
                );
                combat.debug("Skipping combat - victim protected");
                return;
            }
        }
        
        combat.debug("Applying combat tag: " + attacker.getName() + " <-> " + victim.getName());
        combat.directSetCombat(attacker, victim);
        if (victim.getHealth() <= 0) {
            victim.setKiller(attacker);
        }
    }
}