package net.opmasterleo.combat.listener;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BedExplosionListener implements Listener {

    private final Map<UUID, Player> recentBedInteractions = new HashMap<>();
    private final Map<UUID, Long> interactionTimestamps = new HashMap<>();
    private static final long INTERACTION_TIMEOUT = 5000; // 5 seconds

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteractBed(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        String blockTypeName = event.getClickedBlock().getType().name();
        if (!blockTypeName.endsWith("_BED")) return;
        Player player = event.getPlayer();
        World.Environment dimension = player.getWorld().getEnvironment();
        if (dimension != World.Environment.NETHER && dimension != World.Environment.THE_END) return;
        Block bed = event.getClickedBlock();
        UUID bedId = UUID.nameUUIDFromBytes(bed.getLocation().toString().getBytes());
        recentBedInteractions.put(bedId, player);
        interactionTimestamps.put(bedId, System.currentTimeMillis());
        SchedulerUtil.runTaskLaterAsync(Combat.getInstance(), () -> {
            long now = System.currentTimeMillis();
            interactionTimestamps.entrySet().removeIf(entry -> 
                now - entry.getValue() > INTERACTION_TIMEOUT);
            recentBedInteractions.keySet().retainAll(interactionTimestamps.keySet());
        }, 200L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != DamageCause.BLOCK_EXPLOSION) return;

        Combat combat = Combat.getInstance();
        if (!combat.getConfig().getBoolean("link-bed-explosions", true)) return;

        int radius = 6;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radius * radius) continue;
                    Block block = victim.getLocation().add(x, y, z).getBlock();
                    if (block.getType().name().endsWith("_BED")) {
                        UUID bedId = UUID.nameUUIDFromBytes(block.getLocation().toString().getBytes());
                        Player activator = recentBedInteractions.get(bedId);
                        if (activator != null && activator.isOnline()) {
                            if (combat.getSuperVanishManager() != null && combat.getSuperVanishManager().isVanished(activator)) {
                                return;
                            }
                            NewbieProtectionListener protection = combat.getNewbieProtectionListener();
                            if (protection != null) {
                                boolean activatorProtected = protection.isActuallyProtected(activator);
                                boolean victimProtected = protection.isActuallyProtected(victim);
                                if (activatorProtected || victimProtected) {
                                    return;
                                }
                            }
                            if (activator.getUniqueId().equals(victim.getUniqueId())) {
                                if (combat.getConfig().getBoolean("self-combat", false)) {
                                    combat.directSetCombat(victim, victim);
                                }
                            } else {
                                combat.directSetCombat(victim, activator);
                                combat.directSetCombat(activator, victim);
                            }
                            if (victim.getHealth() <= event.getFinalDamage()) {
                                victim.setKiller(activator);
                            }
                            return;
                        }
                    }
                }
            }
        }
    }

    public Player getBedActivator(UUID bedId) {
        return recentBedInteractions.get(bedId);
    }
    
    public void cleanup() {
        recentBedInteractions.clear();
        interactionTimestamps.clear();
    }

    public void registerPotentialExplosion(Location location, Player player) {
        if (location == null || player == null) return;
        UUID bedId = UUID.nameUUIDFromBytes(location.toString().getBytes());
        recentBedInteractions.put(bedId, player);
        interactionTimestamps.put(bedId, System.currentTimeMillis());
        SchedulerUtil.runTaskLaterAsync(Combat.getInstance(), () -> {
            long now = System.currentTimeMillis();
            interactionTimestamps.entrySet().removeIf(entry -> now - entry.getValue() > INTERACTION_TIMEOUT);
            recentBedInteractions.keySet().retainAll(interactionTimestamps.keySet());
        }, 200L);
    }
}