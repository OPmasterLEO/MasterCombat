package net.opmasterleo.combat.handler;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import net.opmasterleo.combat.Combat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PacketHandler extends PacketListenerAbstract {
    private final Combat plugin;
    private final AtomicInteger processingCount = new AtomicInteger(0);
    private final Map<Integer, Entity> entityCache = new ConcurrentHashMap<>(2048);
    private final Map<UUID, Long> throttleMap = new ConcurrentHashMap<>(1024);
    private static final long THROTTLE_TIME = 50L;
    private long lastCleanup = System.currentTimeMillis();

    public PacketHandler(Combat plugin) {
        this.plugin = plugin;
        startCleanupTask();
    }

    private void startCleanupTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (System.currentTimeMillis() - lastCleanup > 60000) {
                entityCache.clear();
                throttleMap.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() > 5000);
                lastCleanup = System.currentTimeMillis();
            }
        }, 1200L, 1200L);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        
        try {
            if (processingCount.incrementAndGet() > 20000) {
                processingCount.decrementAndGet();
                return;
            }
            
            if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
                handleEntityInteract(event, player);
            } else if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
                handleItemUse(event, player);
            } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
                handlePlayerDigging(event, player);
            }
        } finally {
            processingCount.decrementAndGet();
        }
    }

    private void handleEntityInteract(PacketReceiveEvent event, Player player) {
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        int entityId = wrapper.getEntityId();
        
        UUID playerUUID = player.getUniqueId();
        if (isThrottled(playerUUID)) return;
        
        Entity targetEntity = getEntityById(entityId, player);
        if (targetEntity == null) return;
        
        if (targetEntity.getType() == EntityType.END_CRYSTAL) {
            if (plugin.getNewbieProtectionListener() != null && 
                plugin.getNewbieProtectionListener().isActuallyProtected(player)) {
                
                for (Entity nearby : targetEntity.getNearbyEntities(5.0, 5.0, 5.0)) {
                    if (nearby instanceof Player target && 
                        !playerUUID.equals(target.getUniqueId()) &&
                        !plugin.getNewbieProtectionListener().isActuallyProtected(target)) {
                        
                        event.setCancelled(true);
                        player.sendMessage(plugin.getNewbieProtectionListener().getCrystalBlockMessage());
                        return;
                    }
                }
            }
            
            plugin.getCrystalManager().setPlacer(targetEntity, player);
            throttleMap.put(playerUUID, System.currentTimeMillis());
        }
    }
    
    private Entity getEntityById(int entityId, Player player) {
        Entity entity = entityCache.get(entityId);
        if (entity != null) return entity;
        
        for (Entity e : player.getWorld().getEntities()) {
            if (e.getEntityId() == entityId) {
                entityCache.put(entityId, e);
                return e;
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
                plugin.directSetCombat(player, player);
            }
        }
    }

    private void handleItemUse(PacketReceiveEvent event, Player player) {
        ItemStack mainHandItem = player.getInventory().getItemInMainHand();
        if (mainHandItem.getType() != Material.GLOWSTONE) return;
        
        Block targetBlock = player.getTargetBlock(null, 5);
        if (targetBlock != null && targetBlock.getType() == Material.RESPAWN_ANCHOR) {
            if (plugin.getRespawnAnchorListener() != null) {
                plugin.getRespawnAnchorListener().trackAnchorInteraction(targetBlock, player);

                if (plugin.getConfig().getBoolean("self-combat", false)) {
                    plugin.getRespawnAnchorListener().registerPotentialExplosion(targetBlock.getLocation(), player);

                    if (!plugin.isInCombat(player)) {
                        plugin.getRespawnAnchorListener().registerPotentialExplosion(player.getLocation(), player);
                    }
                }
            }
        }
    }
}