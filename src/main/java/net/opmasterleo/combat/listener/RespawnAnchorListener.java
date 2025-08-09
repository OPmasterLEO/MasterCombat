package net.opmasterleo.combat.listener;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RespawnAnchorListener extends Combat.PacketListenerAdapter implements Listener {
    private final Map<UUID, Player> anchorActivators = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activationTimestamps = new ConcurrentHashMap<>();
    private final Map<Location, Player> anchorPlacements = new ConcurrentHashMap<>();
    private static final long INTERACTION_TIMEOUT = 5000;
    private static final Material GLOWSTONE = Material.GLOWSTONE;
    private static final Material RESPAWN_ANCHOR = Material.RESPAWN_ANCHOR;

    public RespawnAnchorListener() {
        Combat combat = Combat.getInstance();
        if (combat != null && combat.isPacketEventsAvailable()) {
            combat.safelyRegisterPacketListener(this);
        }
        SchedulerUtil.runTaskTimerAsync(Combat.getInstance(), this::cleanupExpiredData, 100L, 100L);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            handleUseItem(event);
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            handleBlockPlacement(event);
        }
    }
    
    private void handleUseItem(PacketReceiveEvent event) {
        Player player = (Player) event.getPlayer();
        if (player.getWorld().getEnvironment() == World.Environment.NORMAL) {
            Block targetBlock = player.getTargetBlockExact(5);
            if (targetBlock == null || targetBlock.getType() != RESPAWN_ANCHOR) return;
            ItemStack mainHandItem = player.getInventory().getItemInMainHand();
            ItemStack offHandItem = player.getInventory().getItemInOffHand();
            boolean hasGlowstone = mainHandItem.getType() == GLOWSTONE || offHandItem.getType() == GLOWSTONE;
            Location anchorLoc = targetBlock.getLocation();
            UUID anchorId = UUID.nameUUIDFromBytes(anchorLoc.toString().getBytes());
            
            if (!hasGlowstone) {
                anchorActivators.put(anchorId, player);
                activationTimestamps.put(anchorId, System.currentTimeMillis());
                Combat combat = Combat.getInstance();
                NewbieProtectionListener protection = combat.getNewbieProtectionListener();
                
                if (protection != null && protection.isActuallyProtected(player)) {
                    for (Player nearby : player.getWorld().getPlayers()) {
                        if (nearby.equals(player)) continue;
                        if (nearby.getLocation().distance(anchorLoc) <= 10.0 && 
                            !protection.isActuallyProtected(nearby)) {
                            protection.sendBlockedMessage(player, protection.getAnchorBlockMessage());
                            event.setCancelled(true);
                            return;
                        }
                    }
                }
            }
        }
    }
    
    private void handleBlockPlacement(PacketReceiveEvent event) {
        WrapperPlayClientPlayerBlockPlacement placementPacket = new WrapperPlayClientPlayerBlockPlacement(event);
        Player player = (Player) event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != RESPAWN_ANCHOR && player.getInventory().getItemInOffHand().getType() != RESPAWN_ANCHOR) {
            return;
        }
        
        Vector3i pos = placementPacket.getBlockPosition();
        Location blockLoc = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ());
        anchorPlacements.put(blockLoc, player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        Location location = victim.getLocation();
        Combat combat = Combat.getInstance();
        Block anchorBlock = findNearbyAnchor(location);
        if (anchorBlock == null) return;
        
        UUID anchorId = UUID.nameUUIDFromBytes(anchorBlock.getLocation().toString().getBytes());
        Player activator = anchorActivators.get(anchorId);
        if (activator == null) {
            activator = anchorPlacements.get(anchorBlock.getLocation());
        }
        
        if (activator == null) return;
        
        NewbieProtectionListener protection = combat.getNewbieProtectionListener();
        if (protection != null) {
            boolean activatorProtected = protection.isActuallyProtected(activator);
            boolean victimProtected = protection.isActuallyProtected(victim);
            if (activatorProtected && !victimProtected) {
                event.setCancelled(true);
                protection.sendBlockedMessage(activator, protection.getAnchorBlockMessage());
                return;
            }
            
            if (victimProtected) {
                event.setCancelled(true);
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
            if (victim.getHealth() <= event.getFinalDamage()) {
                victim.setKiller(activator);
            }
        }
    }
    
    private Block findNearbyAnchor(Location location) {
        World world = location.getWorld();
        double explosionPower = Combat.getInstance().getConfig().getDouble("anchor_explosion.power", 5.0);
        int searchRadius = (int) Math.ceil(explosionPower * 2);
        
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -searchRadius; y <= searchRadius; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    if (x*x + y*y + z*z > searchRadius * searchRadius) continue;
                    
                    Block block = world.getBlockAt(
                        location.getBlockX() + x,
                        location.getBlockY() + y,
                        location.getBlockZ() + z
                    );
                    if (block.getType() == RESPAWN_ANCHOR) {
                        return block;
                    }
                }
            }
        }
        return null;
    }
    
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == RESPAWN_ANCHOR) {
            Location blockLoc = event.getBlockPlaced().getLocation();
            anchorPlacements.put(blockLoc, event.getPlayer());
        }
    }
    
    private void cleanupExpiredData() {
        long now = System.currentTimeMillis();
        activationTimestamps.entrySet().removeIf(entry -> now - entry.getValue() > INTERACTION_TIMEOUT);
        anchorActivators.keySet().retainAll(activationTimestamps.keySet());
        if (now % (INTERACTION_TIMEOUT * 10) < 100) {
            anchorPlacements.entrySet().removeIf(entry -> {
                Block block = entry.getKey().getBlock();
                return block == null || block.getType() != RESPAWN_ANCHOR;
            });
        }
    }
    
    public Player getAnchorActivator(UUID anchorId) {
        return anchorActivators.get(anchorId);
    }
    
    public void registerAnchorActivation(Location location, Player player) {
        if (location == null || player == null) return;
        UUID anchorId = UUID.nameUUIDFromBytes(location.toString().getBytes());
        anchorActivators.put(anchorId, player);
        activationTimestamps.put(anchorId, System.currentTimeMillis());
    }
    
    public void cleanup() {
        anchorActivators.clear();
        activationTimestamps.clear();
        anchorPlacements.clear();
    }
}