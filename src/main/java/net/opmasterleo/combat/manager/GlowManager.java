package net.opmasterleo.combat.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import net.opmasterleo.combat.Combat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GlowManager {
    
    private static volatile boolean packetEventsEnabled = true;
    
    private final Set<UUID> glowingPlayers = Collections.synchronizedSet(new HashSet<>());
    private final Map<Integer, UUID> entityIdMap = new ConcurrentHashMap<>();
    private final Map<UUID, Byte> entityFlagsCache = new ConcurrentHashMap<>();
    private final Set<UUID> pendingUpdates = ConcurrentHashMap.newKeySet();
    private final boolean glowingEnabled;
    private final boolean packetEventsAvailable;
    private static final String GLOWING_METADATA_KEY = "mastercombat_glowing";
    private static final long UPDATE_THROTTLE = 100;
    private long lastBatchUpdate = 0;

    public GlowManager() {
        this.glowingEnabled = isGlowingEnabled();
        this.packetEventsAvailable = isPacketEventsAvailable();
        if (glowingEnabled && packetEventsAvailable) {
            startTracking();
            scheduleBatchUpdates();
        }
    }

    private boolean isGlowingEnabled() {
        try {
            Combat combat = Combat.getInstance();
            if (combat == null) return false;
            return combat.getConfig().getBoolean("General.CombatTagGlowing", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isPacketEventsAvailable() {
        try {
            return PacketEvents.getAPI() != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private void startTracking() {
        if (!glowingEnabled || !packetEventsAvailable) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            trackPlayer(player);
        }
    }
    
    private void scheduleBatchUpdates() {
        if (!glowingEnabled || !packetEventsAvailable) return;
        
        net.opmasterleo.combat.util.SchedulerUtil.runTaskTimerAsync(Combat.getInstance(), () -> {
            processUpdates();
        }, 2L, 2L);
    }
    
    private void processUpdates() {
        long now = System.currentTimeMillis();
        if (pendingUpdates.isEmpty() || now - lastBatchUpdate < UPDATE_THROTTLE) return;
        
        lastBatchUpdate = now;
        
        Set<UUID> observers;
        synchronized (pendingUpdates) {
            observers = new HashSet<>(pendingUpdates);
            pendingUpdates.clear();
        }
        
        for (UUID observerId : observers) {
            Player observer = Bukkit.getPlayer(observerId);
            if (observer == null || !observer.isOnline()) continue;
            
            for (UUID targetId : glowingPlayers) {
                if (observerId.equals(targetId)) continue;
                
                Player target = Bukkit.getPlayer(targetId);
                if (target == null || !target.isOnline()) continue;
                
                updateGlowingForPlayer(target, observer);
            }
        }
    }

    public void trackPlayer(Player player) {
        if (!glowingEnabled || !packetEventsAvailable || player == null) return;
        entityIdMap.put(player.getEntityId(), player.getUniqueId());
        player.setMetadata(GLOWING_METADATA_KEY, new FixedMetadataValue(Combat.getInstance(), false));
        pendingUpdates.add(player.getUniqueId());
        if (!glowingPlayers.isEmpty()) {
            for (UUID targetId : glowingPlayers) {
                Player target = Bukkit.getPlayer(targetId);
                if (target != null && !target.equals(player)) {
                    updateGlowingForPlayer(target, player);
                }
            }
        }
    }

    public void untrackPlayer(Player player) {
        if (!glowingEnabled || !packetEventsAvailable || player == null) return;
        entityIdMap.remove(player.getEntityId());
        player.removeMetadata(GLOWING_METADATA_KEY, Combat.getInstance());
        entityFlagsCache.remove(player.getUniqueId());
        pendingUpdates.remove(player.getUniqueId());
        glowingPlayers.remove(player.getUniqueId());
    }

    public void setGlowing(Player player, boolean glowing) {
        if (!glowingEnabled || !packetEventsAvailable || player == null) return;
        if (!packetEventsEnabled) return;
        
        UUID playerId = player.getUniqueId();
        boolean wasGlowing = glowingPlayers.contains(playerId);
        player.setMetadata(GLOWING_METADATA_KEY, new FixedMetadataValue(Combat.getInstance(), glowing));
        if (glowing == wasGlowing) return;
        
        if (glowing) {
            glowingPlayers.add(playerId);
        } else {
            glowingPlayers.remove(playerId);
            entityFlagsCache.remove(playerId);
        }
        
        for (Player observer : Bukkit.getOnlinePlayers()) {
            if (!observer.equals(player)) {
                pendingUpdates.add(observer.getUniqueId());
            }
        }
    }

    public boolean isGlowing(Player player) {
        return player != null && player.hasMetadata(GLOWING_METADATA_KEY) && 
               player.getMetadata(GLOWING_METADATA_KEY).get(0).asBoolean();
    }

    public void updateGlowingForPlayer(Player target, Player observer) {
        if (!glowingEnabled || !packetEventsAvailable) return;
        if (target == null || observer == null) return;
        if (target.equals(observer)) return;

        boolean shouldGlow = glowingPlayers.contains(target.getUniqueId());
        UUID targetId = target.getUniqueId();

        try {
            var playerManager = PacketEvents.getAPI().getPlayerManager();
            if (playerManager == null) return;
            User user = playerManager.getUser(observer.getUniqueId());
            if (user != null) {
                List<EntityData<?>> metadata = new ArrayList<>();
                byte entityFlags = entityFlagsCache.computeIfAbsent(targetId, id -> {
                    byte flags = 0;
                    if (target.getFireTicks() > 0) flags |= 0x01;
                    if (target.isSneaking()) flags |= 0x02;
                    if (target.isSprinting()) flags |= 0x08;
                    if (target.isSwimming()) flags |= 0x10;
                    if (target.isInvisible()) flags |= 0x20;
                    if (target.isGliding()) flags |= 0x80;
                    return flags;
                });
                
                byte finalFlags = (byte)(shouldGlow ? (entityFlags | 0x40) : (entityFlags & ~0x40));
                
                if (EntityDataTypes.BYTE != null) {
                    metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, finalFlags));
                    if (shouldGlow) {
                        entityFlagsCache.put(targetId, (byte)(entityFlags | 0x40));
                    }
                    
                    WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(
                        target.getEntityId(),
                        metadata
                    );

                    playerManager.sendPacket(user, metadataPacket);
                }
            }
        } catch (Exception e) {
            Combat.getInstance().getLogger().warning("Failed to update glowing status: " + (e.getMessage() != null ? e.getMessage() : "unknown error"));
        }
    }
    
    public void updateGlowingForAll() {
        if (!glowingEnabled || !packetEventsAvailable) return;
        

        for (UUID playerId : glowingPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                byte flags = 0;
                if (player.getFireTicks() > 0) flags |= 0x01;
                if (player.isSneaking()) flags |= 0x02;
                if (player.isSprinting()) flags |= 0x08;
                if (player.isSwimming()) flags |= 0x10;
                if (player.isInvisible()) flags |= 0x20;
                flags |= 0x40;
                if (player.isGliding()) flags |= 0x80;
                entityFlagsCache.put(playerId, flags);
            }
        }

        for (Player observer : Bukkit.getOnlinePlayers()) {
            pendingUpdates.add(observer.getUniqueId());
        }
    }

    public void cleanup() {
        if (!glowingEnabled || !packetEventsAvailable) return;
        for (UUID playerId : new HashSet<>(glowingPlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                setGlowing(player, false);
            }
        }
        
        glowingPlayers.clear();
        entityIdMap.clear();
        entityFlagsCache.clear();
        pendingUpdates.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.removeMetadata(GLOWING_METADATA_KEY, Combat.getInstance());
        }
    }
    
    public void forceUpdate(Player player) {
        if (!glowingEnabled || !packetEventsAvailable || player == null) return;
        entityFlagsCache.remove(player.getUniqueId());
        for (Player observer : Bukkit.getOnlinePlayers()) {
            if (!observer.equals(player)) {
                updateGlowingForPlayer(player, observer);
            }
        }
    }
    
    public void disablePacketEventsIntegration() {
        packetEventsEnabled = false;
    }
}