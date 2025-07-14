package net.opmasterleo.combat.listener;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class EndCrystalListener implements Listener {

    private final Map<UUID, Long> recentExplosions = new ConcurrentHashMap<>(256); // Smaller initial capacity
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private static final int MAX_PENDING_TASKS = 10;

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getType() != EntityType.END_CRYSTAL) return;

        Player player = event.getPlayer();
        Entity crystal = event.getRightClicked();
        Combat combat = Combat.getInstance();
        NewbieProtectionListener protection = combat.getNewbieProtectionListener();

        combat.getCrystalManager().setPlacer(crystal, player);

        if (protection != null && protection.isActuallyProtected(player)) {
            for (Entity nearby : crystal.getNearbyEntities(6.0, 6.0, 6.0)) {
                if (nearby instanceof Player target
                    && !player.getUniqueId().equals(target.getUniqueId())
                    && !protection.isActuallyProtected(target)) {
                    event.setCancelled(true);
                    protection.sendBlockedMessage(player, protection.getCrystalBlockMessage());
                    return;
                }
            }
        }

        if (combat.getConfig().getBoolean("self-combat", false)) {
            combat.directSetCombat(player, player);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (event.getEntity().getType() != EntityType.END_CRYSTAL) return;

        Entity crystal = event.getEntity();
        UUID explosionId = UUID.randomUUID();
        recentExplosions.put(explosionId, System.currentTimeMillis());
        crystal.setMetadata("explosion_id", new FixedMetadataValue(
            Combat.getInstance(), explosionId));

        // Limit concurrent async tasks to prevent thread pool saturation
        if (pendingTasks.incrementAndGet() <= MAX_PENDING_TASKS) {
            SchedulerUtil.runTaskLaterAsync(Combat.getInstance(), () -> {
                try {
                    // Batch cleanup for better performance
                    long now = System.currentTimeMillis();
                    recentExplosions.entrySet().removeIf(entry -> now - entry.getValue() > 5000);
                } finally {
                    pendingTasks.decrementAndGet();
                }
            }, 100L);
        } else {
            pendingTasks.decrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!Combat.getInstance().getConfig().getBoolean("link-end-crystals", true)) return;
        
        // Skip if there's no actual damage
        if (event.getFinalDamage() <= 0) return;

        Entity damager = event.getDamager();
        if (damager.getType() != EntityType.END_CRYSTAL) return;

        Player placer = Combat.getInstance().getCrystalManager().getPlacer(damager);
        Combat combat = Combat.getInstance();
        NewbieProtectionListener protection = combat.getNewbieProtectionListener();

        if (event.getEntity() instanceof Player victim) {
            // Maintain all protection checks
            if (combat.getSuperVanishManager() != null && 
                ((placer != null && combat.getSuperVanishManager().isVanished(placer)) || 
                 combat.getSuperVanishManager().isVanished(victim))) {
                event.setCancelled(true);
                return;
            }

            boolean placerProtected = placer != null && protection != null && 
                                     protection.isActuallyProtected(placer);
            boolean victimProtected = protection != null && protection.isActuallyProtected(victim);
            if (placerProtected && !victimProtected) {
                event.setCancelled(true);
                if (placer != null && protection != null) {
                    protection.sendBlockedMessage(placer, protection.getCrystalBlockMessage());
                }
                return;
            }

            if (!placerProtected && victimProtected) {
                event.setCancelled(true);
                if (placer != null && protection != null) {
                    protection.sendBlockedMessage(placer, protection.getAttackerMessage());
                }
                return;
            }
            if (placer != null && !shouldBypass(placer)) {
                boolean selfCombat = Combat.getInstance().getConfig().getBoolean("self-combat", false);
                if (victim.getUniqueId().equals(placer.getUniqueId())) {
                    if (selfCombat) {
                        Combat.getInstance().directSetCombat(victim, victim);
                        if (victim.getHealth() <= event.getFinalDamage()) {
                            Player opponent = combat.getCombatOpponent(victim);
                            if (opponent != null && !opponent.equals(victim)) {
                                victim.setKiller(opponent);
                            }
                        }
                    }
                } else {
                    Combat.getInstance().directSetCombat(victim, placer);
                    Combat.getInstance().directSetCombat(placer, victim);
                    if (victim.getHealth() <= event.getFinalDamage()) {
                        victim.setKiller(placer);
                    }
                }
            } else {
                if (victim.getHealth() <= event.getFinalDamage()) {
                    Player opponent = combat.getCombatOpponent(victim);
                    if (opponent != null) {
                        victim.setKiller(opponent);
                    }
                }
                
                linkCrystalByProximity(damager, victim);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onGeneralDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && 
            event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return;
        }

        NewbieProtectionListener protection = Combat.getInstance().getNewbieProtectionListener();
        if (protection != null && protection.isActuallyProtected(victim)) {
            event.setCancelled(true);
        }
    }

    private void linkCrystalByProximity(Entity crystal, Player victim) {
        List<Entity> nearbyEntities = crystal.getNearbyEntities(4, 4, 4);
        if (nearbyEntities.isEmpty()) return;
        
            nearbyEntities.sort((e1, e2) -> {
            if (!(e1 instanceof Player) && !(e2 instanceof Player)) return 0;
            if (!(e1 instanceof Player)) return 1;
            if (!(e2 instanceof Player)) return -1;
            
            double d1 = e1.getLocation().distanceSquared(crystal.getLocation());
            double d2 = e2.getLocation().distanceSquared(crystal.getLocation());
            return Double.compare(d1, d2);
        });
        
        for (Entity entity : nearbyEntities) {
            if (entity instanceof Player player && !shouldBypass(player) &&
                    (!player.equals(victim) || Combat.getInstance().getConfig().getBoolean("self-combat", false))) {
                Combat.getInstance().getCrystalManager().setPlacer(crystal, player);
                Combat.getInstance().directSetCombat(victim, player);
                Combat.getInstance().directSetCombat(player, victim);
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntityType() != EntityType.END_CRYSTAL) return;
        Entity crystal = event.getEntity();
        Combat.getInstance().getCrystalManager().removeCrystal(crystal);
    }

    private boolean shouldBypass(Player player) {
        return player == null || 
               player.getGameMode() == org.bukkit.GameMode.CREATIVE || 
               player.getGameMode() == org.bukkit.GameMode.SPECTATOR;
    }
}