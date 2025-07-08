package net.opmasterleo.combat.listener;

import net.opmasterleo.combat.Combat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
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

        Material blockType = event.getClickedBlock().getType();
        if (!blockType.name().endsWith("_BED")) return;

        Player player = event.getPlayer();
        World.Environment dimension = player.getWorld().getEnvironment();
        
        // Only track beds in dimensions where they explode
        if (dimension != World.Environment.NETHER && dimension != World.Environment.THE_END) return;
        
        Block bed = event.getClickedBlock();
        UUID bedId = UUID.nameUUIDFromBytes(bed.getLocation().toString().getBytes());
        recentBedInteractions.put(bedId, player);
        interactionTimestamps.put(bedId, System.currentTimeMillis());
        
        // Clean up old entries after some time
        Bukkit.getScheduler().runTaskLaterAsynchronously(Combat.getInstance(), () -> {
            interactionTimestamps.entrySet().removeIf(entry -> 
                System.currentTimeMillis() - entry.getValue() > INTERACTION_TIMEOUT);
            recentBedInteractions.keySet().retainAll(interactionTimestamps.keySet());
        }, 200L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;

        Combat combat = Combat.getInstance();
        if (!combat.getConfig().getBoolean("link-bed-explosions", true)) return;

        // Find nearby recently interacted beds
        for (Entity entity : victim.getNearbyEntities(6, 6, 6)) {
            // Check all blocks in a small radius around this entity
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Block block = entity.getLocation().add(x, y, z).getBlock();
                        if (block.getType().name().endsWith("_BED")) {
                            UUID bedId = UUID.nameUUIDFromBytes(block.getLocation().toString().getBytes());
                            Player activator = recentBedInteractions.get(bedId);
                            
                            if (activator != null && activator.isOnline()) {
                                if (activator.getUniqueId().equals(victim.getUniqueId())) {
                                    if (combat.getConfig().getBoolean("self-combat", false)) {
                                        combat.directSetCombat(victim, victim);
                                    }
                                } else {
                                    combat.directSetCombat(victim, activator);
                                    combat.directSetCombat(activator, victim);
                                    
                                    // Set the killer for attribution if this is fatal damage
                                    if (victim.getHealth() <= event.getFinalDamage()) {
                                        victim.setKiller(activator);
                                    }
                                }
                                return;
                            }
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
}
