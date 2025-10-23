package net.opmasterleo.combat.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;

public class DirectCombatListener implements PacketListener, Listener {

    private static final long PACKET_THROTTLE_MS = 50;
    private final Map<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();

    public DirectCombatListener() {
    }

    public void initialize(Combat plugin) {
        if (plugin == null) return;
        try {
            if (plugin.isPacketEventsAvailable()) {
                plugin.safelyRegisterPacketListener(this);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        
        try {
            Player attacker = (Player) event.getPlayer();
            if (attacker == null) return;
            long currentTime = System.currentTimeMillis();
            Long lastTime = lastPacketTime.get(attacker.getUniqueId());
            if (lastTime != null && currentTime - lastTime < PACKET_THROTTLE_MS) {
                event.setCancelled(true);
                return;
            }
            lastPacketTime.put(attacker.getUniqueId(), currentTime);

            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                return;
            }

            Combat combat = Combat.getInstance();
            int entityId = packet.getEntityId();
            if (!combat.isCombatEnabled() || attacker.isDead()) {
                return;
            }

            Entity target = combat.getEntityManager().getEntity(entityId);
            if (!(target instanceof Player victim) || victim.isDead()) {
                return;
            }

            if (attacker.getGameMode() == GameMode.CREATIVE || 
                attacker.getGameMode() == GameMode.SPECTATOR || 
                victim.getGameMode() == GameMode.CREATIVE || 
                victim.getGameMode() == GameMode.SPECTATOR) {
                return;
            }

            combat.getCombatWorkerPool().execute(() -> {
                try {
                    if (combat.canDamage(attacker, victim)) {
                        SchedulerUtil.runTask(combat, () -> handleCombat(attacker, victim));
                    }
                } catch (Exception e) {
                    combat.debug("Error in async combat processing: " + e.getMessage());
                }
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
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        Entity damager = event.getDamager();
        switch (damager) {
            case Player attackerPlayer -> attacker = attackerPlayer;
            case Projectile projectile -> {
                if (projectile.getShooter() instanceof Player shooter) {
                    attacker = shooter;
                    String projectileType = damager.getType().name().toUpperCase();
                    boolean isInList = combat.getIgnoredProjectiles().contains(projectileType);
                    boolean shouldSkip = "blacklist".equalsIgnoreCase(combat.getProjectileMode()) ? isInList : !isInList;
                    if (shouldSkip) {
                        combat.debug("Skipping combat - projectile " + projectileType + " filtered by " + combat.getProjectileMode() + " mode");
                        return;
                    }
                }
            }
            default -> {}
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

        combat.debug("Direct combat detected: " + attacker.getName() + " -> " + victim.getName() + 
                    " (Final damage: " + event.getFinalDamage() + ", Original: " + event.getDamage() + ")");
        handleCombat(attacker, victim);
    }
 
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player shooter)) return;
        
        Combat combat = Combat.getInstance();
        if (!combat.isCombatEnabled() || !combat.isCombatEnabledInWorld(shooter)) return;
        
        Projectile projectile = event.getEntity();
        String projectileType = projectile.getType().name().toUpperCase();
        
        boolean isInList = combat.getIgnoredProjectiles().contains(projectileType);
        boolean shouldSkip = "blacklist".equalsIgnoreCase(combat.getProjectileMode()) ? isInList : !isInList;
        if (shouldSkip) {
            combat.debug("Ignoring projectile " + projectileType + " (filtered by " + combat.getProjectileMode() + " mode)");
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
        
        boolean isInList = combat.getIgnoredProjectiles().contains(projectileType);
        boolean shouldSkip = "blacklist".equalsIgnoreCase(combat.getProjectileMode()) ? isInList : !isInList;
        if (shouldSkip) {
            combat.debug("Ignoring projectile hit " + projectileType + " (filtered by " + combat.getProjectileMode() + " mode)");
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