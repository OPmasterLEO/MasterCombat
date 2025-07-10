package net.opmasterleo.combat.handler;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;

import net.opmasterleo.combat.Combat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class PacketHandler extends PacketListenerAbstract {
    private final Combat plugin;
    private final AtomicInteger processingCount = new AtomicInteger(0);
    private final Map<Integer, Entity> entityCache = new ConcurrentHashMap<>(512);
    private final Map<UUID, Long> throttleMap = new ConcurrentHashMap<>(256);
    private final Map<UUID, BukkitTask> pendingCombatTasks = new ConcurrentHashMap<>(64);
    private final Set<UUID> pendingMetadataUpdates = ConcurrentHashMap.newKeySet();
    private volatile boolean isShuttingDown = false;
    
    private static final long THROTTLE_TIME = 50L;
    private static final long ENTITY_CACHE_CLEANUP_INTERVAL = 60000L;
    private long lastCleanup = System.currentTimeMillis();
    
    // Attack types for more precise tracking
    private enum AttackType {
        MELEE,
        RANGED,
        CRYSTAL,
        ANCHOR,
        UNKNOWN
    }

    public PacketHandler(Combat plugin) {
        this.plugin = plugin;
        startCleanupTask();
    }

    private void startCleanupTask() {
        // Only schedule if plugin is still enabled
        if (plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                    if (!plugin.isEnabled() || isShuttingDown) return;
                    
                    long now = System.currentTimeMillis();
                    if (now - lastCleanup > ENTITY_CACHE_CLEANUP_INTERVAL) {
                        entityCache.clear();
                        throttleMap.entrySet().removeIf(entry -> now - entry.getValue() > 5000);
                        lastCleanup = now;
                    }
                }, 1200L, 1200L);
            } catch (IllegalPluginAccessException e) {
                // Plugin is disabled, don't schedule
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // Early return if plugin is disabled or shutting down
        if (!plugin.isEnabled() || isShuttingDown) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        
        try {
            // Limit concurrent processing to avoid thread pool saturation
            if (processingCount.incrementAndGet() > 5000) {
                processingCount.decrementAndGet();
                return;
            }
            
            if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
                handleEntityInteract(event, player);
            } else if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
                handleItemUse(event, player);
            } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
                handlePlayerDigging(event, player);
            } else if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
                handlePlayerAnimation(event, player);
            }
        } catch (Exception e) {
            if (plugin.isEnabled()) {
                plugin.getLogger().log(Level.WARNING, "Error processing packet: " + e.getMessage(), e);
            }
        } finally {
            processingCount.decrementAndGet();
        }
    }
    
    @Override
    public void onPacketSend(PacketSendEvent event) {
        // Early return if plugin is disabled or shutting down
        if (!plugin.isEnabled() || isShuttingDown) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        
        // Handle entity metadata packets for glowing effect
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            handleEntityMetadata(event, player);
        }
    }
    
    private void handleEntityMetadata(PacketSendEvent event, Player player) {
        if (!plugin.getConfig().getBoolean("CombatTagGlowing.Enabled", false)) return;
        
        WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(event);
        int entityId = metadataPacket.getEntityId();
        
        // If this is a glowing update we're expecting, don't interfere
        UUID playerUUID = player.getUniqueId();
        if (pendingMetadataUpdates.remove(playerUUID)) {
            return;
        }
        
        // Otherwise, check if this entity should be glowing in combat
        Entity entity = getEntityById(entityId, player);
        if (entity instanceof Player targetPlayer) {
            if (plugin.isInCombat(targetPlayer) && plugin.getGlowManager() != null) {
                // Let the combat system handle the glowing effect
                event.setCancelled(true);
                
                // Instead of scheduling a task, apply the update directly
                try {
                    if (plugin.isEnabled() && !isShuttingDown && plugin.getGlowManager() != null) {
                        pendingMetadataUpdates.add(playerUUID);
                        plugin.getGlowManager().updateGlowingForPlayer(targetPlayer, player);
                        pendingMetadataUpdates.remove(playerUUID);
                    }
                } catch (Exception e) {
                    // Ignore exceptions during shutdown
                }
            }
        }
    }

    private void handleEntityInteract(PacketReceiveEvent event, Player player) {
        if (!plugin.isEnabled() || isShuttingDown) return;
        
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        int entityId = wrapper.getEntityId();
        WrapperPlayClientInteractEntity.InteractAction action = wrapper.getAction();
        
        // Skip if throttled to prevent spam
        UUID playerUUID = player.getUniqueId();
        if (isThrottled(playerUUID)) return;
        
        Entity targetEntity = getEntityById(entityId, player);
        if (targetEntity == null) return;
        
        // Handle different interaction types
        if (action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            handleCombatInteraction(player, targetEntity, AttackType.MELEE);
        } else if (targetEntity.getType() == EntityType.END_CRYSTAL) {
            if (plugin.isEnabled() && !isShuttingDown && plugin.getCrystalManager() != null) {
                plugin.getCrystalManager().setPlacer(targetEntity, player);
            }
            handleCrystalInteraction(player, targetEntity);
        }
        
        throttleMap.put(playerUUID, System.currentTimeMillis());
    }
    
    private void handlePlayerAnimation(PacketReceiveEvent event, Player player) {
        if (!plugin.isEnabled() || isShuttingDown) return;
        
        // This is often a swing animation which could indicate an attack attempt
        // We can use this for more precise combat detection in some cases
        
        // For now, just track the animation for potential future use
        if (plugin.isInCombat(player)) {
            // Refresh their combat timer if they're swinging during combat
            // This helps prevent people from escaping combat by just barely avoiding hits
            if (plugin.getConfig().getBoolean("refresh-on-swing", false)) {
                plugin.keepPlayerInCombat(player);
            }
        }
    }
    
    private void handleCrystalInteraction(Player player, Entity crystal) {
        if (!plugin.isEnabled() || isShuttingDown) return;
        
        // First check if crystal is valid before using it
        if (!crystal.isValid()) return;
        
        if (plugin.getNewbieProtectionListener() != null && 
            plugin.getNewbieProtectionListener().isActuallyProtected(player)) {
            
            for (Entity nearby : crystal.getNearbyEntities(6.0, 6.0, 6.0)) {
                if (nearby instanceof Player target && 
                    !player.getUniqueId().equals(target.getUniqueId()) &&
                    !plugin.getNewbieProtectionListener().isActuallyProtected(target)) {
                    
                    // Apply the effect directly instead of scheduling
                    // Removed redundant crystal.isValid() check since we check at the beginning
                    if (plugin.getNewbieProtectionListener() != null) {
                        plugin.getNewbieProtectionListener().sendBlockedMessage(
                            player, plugin.getNewbieProtectionListener().getCrystalBlockMessage());
                    }
                    return;
                }
            }
        }
        
        if (plugin.getConfig().getBoolean("self-combat", false)) {
            applyCombatDirectly(player, player);
        }
    }
    
    private void handleCombatInteraction(Player attacker, Entity target, AttackType attackType) {
        // Skip if either entity is invalid or plugin is disabled
        if (target == null || !plugin.isEnabled() || isShuttingDown) return;
        
        // Handle player attacking another player
        if (target instanceof Player victim) {
            // Check vanish status
            if (plugin.getSuperVanishManager() != null && 
                (plugin.getSuperVanishManager().isVanished(attacker) ||
                 plugin.getSuperVanishManager().isVanished(victim))) {
                return;
            }
            if (plugin.getWorldGuardUtil() != null && 
                (plugin.getWorldGuardUtil().isPvpDenied(attacker) || 
                 plugin.getWorldGuardUtil().isPvpDenied(victim))) {
                return;
            }
            
            // Check protection status
            if (plugin.getNewbieProtectionListener() != null) {
                boolean attackerProtected = plugin.getNewbieProtectionListener().isActuallyProtected(attacker);
                boolean victimProtected = plugin.getNewbieProtectionListener().isActuallyProtected(victim);
                
                if ((attackerProtected && !victimProtected) || (!attackerProtected && victimProtected)) {
                    return;
                }
            }
            
            // Apply combat directly instead of scheduling
            applyCombatDirectly(attacker, victim);
        }
    }
    
    // Apply combat directly without scheduling
    private void applyCombatDirectly(Player attacker, Player victim) {
        try {
            if (plugin.isEnabled() && !isShuttingDown && attacker.isValid() && victim.isValid()) {
                plugin.directSetCombat(attacker, victim);
            }
        } catch (Exception e) {
            // Ignore exceptions during shutdown
        }
    }
    
    private Entity getEntityById(int entityId, Player player) {
        if (!plugin.isEnabled() || isShuttingDown) return null;
        
        Entity entity = entityCache.get(entityId);
        if (entity != null && entity.isValid()) return entity;
        
        // Try from player's world first as optimization
        for (Entity e : player.getWorld().getEntities()) {
            if (e.getEntityId() == entityId) {
                entityCache.put(entityId, e);
                return e;
            }
        }
        
        // Try other worlds if necessary
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            if (world.equals(player.getWorld())) continue;
            
            for (Entity e : world.getEntities()) {
                if (e.getEntityId() == entityId) {
                    entityCache.put(entityId, e);
                    return e;
                }
            }
        }
        return null;
    }
    
    private boolean isThrottled(UUID uuid) {
        Long lastProcess = throttleMap.get(uuid);
        return lastProcess != null && System.currentTimeMillis() - lastProcess < THROTTLE_TIME;
    }
    
    private void handlePlayerDigging(PacketReceiveEvent event, Player player) {
        if (!plugin.isEnabled() || isShuttingDown) return;
        
        WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
        if (wrapper.getAction() != DiggingAction.START_DIGGING) return;
        
        Block block = player.getWorld().getBlockAt(
            wrapper.getBlockPosition().getX(), 
            wrapper.getBlockPosition().getY(), 
            wrapper.getBlockPosition().getZ()
        );
        
        if (block.getType() == Material.RESPAWN_ANCHOR) {
            if (plugin.getRespawnAnchorListener() != null) {
                plugin.getRespawnAnchorListener().trackAnchorInteraction(block, player);
            }
            
            boolean selfCombat = plugin.getConfig().getBoolean("self-combat", false);
            if (selfCombat && !plugin.isInCombat(player) && plugin.isCombatEnabled()) {
                applyCombatDirectly(player, player);
            }
        }
    }

    private void handleItemUse(PacketReceiveEvent event, Player player) {
        if (!plugin.isEnabled() || isShuttingDown) return;
        
        ItemStack mainHandItem = player.getInventory().getItemInMainHand();
        
        if (mainHandItem.getType() == Material.GLOWSTONE) {
            Block targetBlock = player.getTargetBlock(null, 5);
            if (targetBlock != null && targetBlock.getType() == Material.RESPAWN_ANCHOR) {
                if (plugin.getRespawnAnchorListener() != null) {
                    plugin.getRespawnAnchorListener().trackAnchorInteraction(targetBlock, player);

                    if (plugin.getConfig().getBoolean("self-combat", false)) {
                        plugin.getRespawnAnchorListener().registerPotentialExplosion(targetBlock.getLocation(), player);
                        applyCombatDirectly(player, player);
                    }
                }
            }
        }
    }
    
    /**
     * Cleanup method to be called on plugin disable
     */
    public void cleanup() {
        isShuttingDown = true; // Set flag to indicate shutdown
        
        // Cancel all pending tasks
        pendingCombatTasks.values().forEach(task -> {
            try {
                task.cancel();
            } catch (Exception ignored) {
                // Task might already be cancelled
            }
        });
        
        entityCache.clear();
        throttleMap.clear();
        pendingCombatTasks.clear();
        pendingMetadataUpdates.clear();
    }
}