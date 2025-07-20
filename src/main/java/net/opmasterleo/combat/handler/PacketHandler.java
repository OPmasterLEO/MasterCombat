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
import net.opmasterleo.combat.listener.NewbieProtectionListener;
import net.opmasterleo.combat.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

    private final Map<UUID, Long> lastArmSwing = new ConcurrentHashMap<>(64);

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
        if (plugin.isEnabled()) {
            try {
                SchedulerUtil.runTaskTimerAsync(plugin, () -> {
                    if (!plugin.isEnabled() || isShuttingDown) return;
                    
                    long now = System.currentTimeMillis();
                    if (now - lastCleanup > ENTITY_CACHE_CLEANUP_INTERVAL) {
                        entityCache.clear();
                        throttleMap.entrySet().removeIf(entry -> now - entry.getValue() > 5000);
                        lastCleanup = now;
                    }
                }, 1200L, 1200L);
            } catch (IllegalPluginAccessException e) {
                plugin.getLogger().warning("Failed to start cleanup task: " + e.getMessage());
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!plugin.isEnabled() || isShuttingDown) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        try {
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
        if (!plugin.isEnabled() || isShuttingDown) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        if (event.getPacketType() == PacketType.Play.Server.EXPLOSION) {
            handleExplosionPacket(event, player);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            handleEntityMetadata(event, player);
        }
    }

    private void handleExplosionPacket(PacketSendEvent event, Player player) {
        Location explosionLocation = player.getLocation();
        Combat combat = Combat.getInstance();
        if (combat.getRespawnAnchorListener() != null) {
            combat.getRespawnAnchorListener().registerPotentialExplosion(explosionLocation, player);
        }
        if (combat.getBedExplosionListener() != null) {
            combat.getBedExplosionListener().registerPotentialExplosion(explosionLocation, player);
        }
    }

    private void handleEntityMetadata(PacketSendEvent event, Player player) {
        if (!plugin.getConfig().getBoolean("CombatTagGlowing.Enabled", false)) return;
        
        WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(event);
        int entityId = metadataPacket.getEntityId();
        UUID playerUUID = player.getUniqueId();
        if (pendingMetadataUpdates.remove(playerUUID)) {
            return;
        }

        Entity entity = getEntityById(entityId, player);
        if (entity instanceof Player targetPlayer) {
            if (plugin.isInCombat(targetPlayer) && plugin.getGlowManager() != null) {
                event.setCancelled(true);
                try {
                    if (plugin.isEnabled() && !isShuttingDown && plugin.getGlowManager() != null) {
                        pendingMetadataUpdates.add(playerUUID);
                        plugin.getGlowManager().updateGlowingForPlayer(targetPlayer, player);
                        pendingMetadataUpdates.remove(playerUUID);
                    }
                } catch (Exception e) {
                }
            }
        }
    }

    private void handleEntityInteract(PacketReceiveEvent event, Player player) {
        if (!plugin.isEnabled() || isShuttingDown) return;
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        int entityId = wrapper.getEntityId();
        WrapperPlayClientInteractEntity.InteractAction action = wrapper.getAction();
        UUID playerUUID = player.getUniqueId();
        if (isThrottled(playerUUID)) return;
        Entity targetEntity = getEntityById(entityId, player);
        if (targetEntity == null) return;
        if (action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            if (isSwingingArm(player)) {
                handleCombatInteraction(player, targetEntity, AttackType.MELEE);
            }
        } else if (targetEntity.getType() == EntityType.END_CRYSTAL) {
            if (plugin.isEnabled() && !isShuttingDown && plugin.getCrystalManager() != null) {
                plugin.getCrystalManager().setPlacer(targetEntity, player);
            }
            handleCrystalInteraction(player, targetEntity);
        }
        
        throttleMap.put(playerUUID, System.currentTimeMillis());
    }
    private boolean isSwingingArm(Player player) {
        try {
            return System.currentTimeMillis() - lastArmSwing.getOrDefault(player.getUniqueId(), 0L) < 200;
        } catch (Exception e) {
            return true;
        }
    }

    private void handlePlayerAnimation(PacketReceiveEvent event, Player player) {
        if (!plugin.isEnabled() || isShuttingDown) return;
        UUID playerUUID = player.getUniqueId();
        lastArmSwing.put(playerUUID, System.currentTimeMillis());
        if (plugin.isInCombat(player)) {
            if (plugin.getConfig().getBoolean("refresh-on-swing", false)) {
                plugin.keepPlayerInCombat(player);
            }
        } else {
            if (plugin.getConfig().getBoolean("detect-combat-from-swings", false)) {
                for (Entity entity : player.getNearbyEntities(3.0, 3.0, 3.0)) {
                    if (entity instanceof Player target && !target.equals(player)) {
                        if (isPlayerFacingEntity(player, target)) {
                            if (plugin.canDamage(player, target)) {
                                SchedulerUtil.runTaskLater(plugin, () -> {
                                    if (!plugin.isInCombat(player) && plugin.isCombatEnabled()) {
                                        if (isHoldingWeapon(player)) {
                                            plugin.directSetCombat(player, target);
                                        }
                                    }
                                }, 2L);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }
    
    private boolean isPlayerFacingEntity(Player player, Entity entity) {
        org.bukkit.util.Vector playerDirection = player.getLocation().getDirection().normalize();
        org.bukkit.util.Vector playerToEntity = entity.getLocation().toVector().subtract(player.getLocation().toVector()).normalize();
        double dotProduct = playerDirection.dot(playerToEntity);
        return dotProduct > 0.8;
    }
    
    private boolean isHoldingWeapon(Player player) {
        Material material = player.getInventory().getItemInMainHand().getType();
        return material.name().endsWith("_SWORD") || 
               material.name().endsWith("_AXE") || 
               material == Material.TRIDENT;
    }
    
    private void handleCrystalInteraction(Player player, Entity crystal) {
        if (!plugin.isEnabled() || isShuttingDown) return;
        if (!crystal.isValid()) return;
        
        if (plugin.getNewbieProtectionListener() != null && 
            plugin.getNewbieProtectionListener().isActuallyProtected(player)) {
            
            for (Entity nearby : crystal.getNearbyEntities(6.0, 6.0, 6.0)) {
                if (nearby instanceof Player target && 
                    !player.getUniqueId().equals(target.getUniqueId()) &&
                    !plugin.getNewbieProtectionListener().isActuallyProtected(target)) {
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
        if (target == null || !plugin.isEnabled() || isShuttingDown) return;
        if (target instanceof Player victim) {
            if (plugin.getWorldGuardUtil() != null && 
                (plugin.getWorldGuardUtil().isPvpDenied(attacker) || 
                 plugin.getWorldGuardUtil().isPvpDenied(victim))) {
                return;
            }
            
            if (plugin.getSuperVanishManager() != null && 
                (plugin.getSuperVanishManager().isVanished(attacker) || 
                 plugin.getSuperVanishManager().isVanished(victim))) {
                return;
            }
            
            NewbieProtectionListener protection = plugin.getNewbieProtectionListener();
            if (protection != null) {
                boolean attackerProtected = protection.isActuallyProtected(attacker);
                boolean victimProtected = protection.isActuallyProtected(victim);
                if (attackerProtected || victimProtected) {
                    return;
                }
            }
        }
    }

    private void applyCombatDirectly(Player attacker, Player victim) {
        try {
            if (plugin.isEnabled() && !isShuttingDown && attacker.isValid() && victim.isValid()) {
                plugin.directSetCombat(attacker, victim);
            }
        } catch (Exception e) {
        }
    }
    
    private boolean isThrottled(UUID uuid) {
        Long lastProcess = throttleMap.get(uuid);
        return lastProcess != null && System.currentTimeMillis() - lastProcess < THROTTLE_TIME;
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
                
                boolean selfCombat = plugin.getConfig().getBoolean("self-combat", false);
                if (selfCombat && !plugin.isInCombat(player) && plugin.isCombatEnabled()) {
                    applyCombatDirectly(player, player);
                }
            }
        }
    }
    
    private Entity getEntityById(int entityId, Player player) {
        if (!plugin.isEnabled() || isShuttingDown) return null;
        Entity entity = entityCache.get(entityId);
        if (entity != null && entity.isValid()) return entity;
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                for (Entity e : player.getWorld().getEntities()) {
                    if (e.getEntityId() == entityId) {
                        entityCache.put(entityId, e);
                        return e;
                    }
                }
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
            }).get();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to fetch entity synchronously: " + e.getMessage());
            return null;
        }
    }

    public void cleanup() {
        isShuttingDown = true;
        pendingCombatTasks.values().forEach(task -> {
            try {
                task.cancel();
            } catch (Exception ignored) {
            }
        });
        
        entityCache.clear();
        throttleMap.clear();
        pendingCombatTasks.clear();
        pendingMetadataUpdates.clear();
        lastArmSwing.clear();
    }

    public void register() {
        try {
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(this);
            plugin.getLogger().info("PacketHandler registered successfully");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to register PacketHandler: " + e.getMessage());
        }
    }
}