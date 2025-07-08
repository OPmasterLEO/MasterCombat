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

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PacketHandler extends PacketListenerAbstract {
    private final Combat plugin;
    private final AtomicInteger processingCount = new AtomicInteger(0);
    private final Map<Integer, Entity> entityCache = new ConcurrentHashMap<>(512);
    private final Map<UUID, Long> throttleMap = new ConcurrentHashMap<>(256);
    private final Map<UUID, BukkitTask> pendingCombatTasks = new ConcurrentHashMap<>(64);
    private final Set<UUID> pendingMetadataUpdates = ConcurrentHashMap.newKeySet();
    
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
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            if (now - lastCleanup > ENTITY_CACHE_CLEANUP_INTERVAL) {
                entityCache.clear();
                throttleMap.entrySet().removeIf(entry -> now - entry.getValue() > 5000);
                lastCleanup = now;
            }
        }, 1200L, 1200L);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
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
        } finally {
            processingCount.decrementAndGet();
        }
    }
    
    @Override
    public void onPacketSend(PacketSendEvent event) {
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
                
                // Schedule a proper glowing update
                pendingMetadataUpdates.add(playerUUID);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.getGlowManager() != null) {
                        plugin.getGlowManager().updateGlowingForPlayer(targetPlayer, player);
                    }
                    pendingMetadataUpdates.remove(playerUUID);
                });
            }
        }
    }

    private void handleEntityInteract(PacketReceiveEvent event, Player player) {
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
            plugin.getCrystalManager().setPlacer(targetEntity, player);
            handleCrystalInteraction(player, targetEntity);
        }
        
        throttleMap.put(playerUUID, System.currentTimeMillis());
    }
    
    private void handlePlayerAnimation(PacketReceiveEvent event, Player player) {
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
        if (plugin.getNewbieProtectionListener() != null && 
            plugin.getNewbieProtectionListener().isActuallyProtected(player)) {
            
            for (Entity nearby : crystal.getNearbyEntities(6.0, 6.0, 6.0)) {
                if (nearby instanceof Player target && 
                    !player.getUniqueId().equals(target.getUniqueId()) &&
                    !plugin.getNewbieProtectionListener().isActuallyProtected(target)) {
                    
                    // Schedule a task to cancel the effect of this packet if possible
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (crystal.isValid()) {
                            plugin.getNewbieProtectionListener().sendBlockedMessage(
                                player, plugin.getNewbieProtectionListener().getCrystalBlockMessage());
                        }
                    });
                    return;
                }
            }
        }
        
        if (plugin.getConfig().getBoolean("self-combat", false)) {
            scheduleCombatTag(player, player, 1);
        }
    }
    
    private void handleCombatInteraction(Player attacker, Entity target, AttackType attackType) {
        // Skip if either entity is invalid or protected
        if (target == null) return;
        
        // Handle player attacking another player
        if (target instanceof Player victim) {
            // Check vanish status
            if (plugin.getSuperVanishManager() != null && 
                (plugin.getSuperVanishManager().isVanished(attacker) ||
                 plugin.getSuperVanishManager().isVanished(victim))) {
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
            
            // Schedule combat tag with appropriate delay based on attack type
            int delay = getDelayForAttackType(attackType);
            scheduleCombatTag(attacker, victim, delay);
        }
    }
    
    private int getDelayForAttackType(AttackType attackType) {
        switch (attackType) {
            case MELEE: return 1; // Almost immediate
            case RANGED: return 2; // Slight delay for projectiles
            case CRYSTAL: return 3; // Longer delay for crystals
            case ANCHOR: return 3; // Longer delay for anchors
            default: return 2;
        }
    }
    
    private void scheduleCombatTag(Player attacker, Player victim, int delayTicks) {
        UUID attackerUUID = attacker.getUniqueId();
        
        // Cancel any existing pending tag operations
        BukkitTask existingTask = pendingCombatTasks.remove(attackerUUID);
        if (existingTask != null) existingTask.cancel();
        
        // Schedule the new tagging operation
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (attacker.isValid() && victim.isValid()) {
                plugin.directSetCombat(attacker, victim);
                pendingCombatTasks.remove(attackerUUID);
            }
        }, delayTicks);
        
        pendingCombatTasks.put(attackerUUID, task);
    }
    
    private Entity getEntityById(int entityId, Player player) {
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
                scheduleCombatTag(player, player, getDelayForAttackType(AttackType.ANCHOR));
            }
        }
    }

    private void handleItemUse(PacketReceiveEvent event, Player player) {
        ItemStack mainHandItem = player.getInventory().getItemInMainHand();
        
        if (mainHandItem.getType() == Material.GLOWSTONE) {
            Block targetBlock = player.getTargetBlock(null, 5);
            if (targetBlock != null && targetBlock.getType() == Material.RESPAWN_ANCHOR) {
                if (plugin.getRespawnAnchorListener() != null) {
                    plugin.getRespawnAnchorListener().trackAnchorInteraction(targetBlock, player);

                    if (plugin.getConfig().getBoolean("self-combat", false)) {
                        plugin.getRespawnAnchorListener().registerPotentialExplosion(targetBlock.getLocation(), player);
                        scheduleCombatTag(player, player, getDelayForAttackType(AttackType.ANCHOR));
                    }
                }
            }
        }
    }
    
    /**
     * Cleanup method to be called on plugin disable
     */
    public void cleanup() {
        entityCache.clear();
        throttleMap.clear();
        
        // Cancel all pending tasks
        pendingCombatTasks.values().forEach(BukkitTask::cancel);
        pendingCombatTasks.clear();
        pendingMetadataUpdates.clear();
    }
}