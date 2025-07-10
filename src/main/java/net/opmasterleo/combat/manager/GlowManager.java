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

    private final Set<UUID> glowingPlayers = Collections.synchronizedSet(new HashSet<>());
    private final Map<Integer, UUID> entityIdMap = new ConcurrentHashMap<>();
    private final boolean glowingEnabled;
    private final boolean packetEventsAvailable;
    private static final String GLOWING_METADATA_KEY = "mastercombat_glowing";

    public GlowManager() {
        this.glowingEnabled = isGlowingEnabled();
        this.packetEventsAvailable = isPacketEventsAvailable();
        if (glowingEnabled && packetEventsAvailable) {
            startTracking();
        }
    }

    private boolean isGlowingEnabled() {
        try {
            return org.bukkit.Bukkit.getPluginManager()
                .getPlugin("MasterCombat") != null &&
                net.opmasterleo.combat.Combat.getInstance()
                    .getConfig().getBoolean("CombatTagGlowing.Enabled", false);
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

    public void trackPlayer(Player player) {
        if (!glowingEnabled || !packetEventsAvailable) return;
        entityIdMap.put(player.getEntityId(), player.getUniqueId());
        
        // Store initial glow state in metadata
        player.setMetadata(GLOWING_METADATA_KEY, new FixedMetadataValue(Combat.getInstance(), false));
    }

    public void untrackPlayer(Player player) {
        if (!glowingEnabled || !packetEventsAvailable) return;
        entityIdMap.remove(player.getEntityId());
        player.removeMetadata(GLOWING_METADATA_KEY, Combat.getInstance());
    }

    public void setGlowing(Player player, boolean glowing) {
        if (!glowingEnabled || !packetEventsAvailable) return;
        
        // Store glow state in metadata
        player.setMetadata(GLOWING_METADATA_KEY, new FixedMetadataValue(Combat.getInstance(), glowing));
        
        if (glowing) {
            glowingPlayers.add(player.getUniqueId());
        } else {
            glowingPlayers.remove(player.getUniqueId());
        }
        
        // Update glowing for all online players
        for (Player observer : Bukkit.getOnlinePlayers()) {
            updateGlowingForPlayer(player, observer);
        }
    }

    public boolean isGlowing(Player player) {
        return player.hasMetadata(GLOWING_METADATA_KEY) && 
               player.getMetadata(GLOWING_METADATA_KEY).get(0).asBoolean();
    }

    public void updateGlowingForPlayer(Player target, Player observer) {
        if (!glowingEnabled || !packetEventsAvailable) return;
        if (target == null || observer == null) return;
        
        // Don't show glowing effect to the player themselves
        if (target.equals(observer)) return;
        
        boolean shouldGlow = glowingPlayers.contains(target.getUniqueId());
        
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(observer);
            if (user != null) {
                // Create entity metadata packet to update glowing status
                List<EntityData<?>> metadata = new ArrayList<>();
                
                // Get the current entity flags
                byte entityFlags = 0;
                // Check fire ticks instead of using isOnFire which doesn't exist
                if (target.getFireTicks() > 0) entityFlags |= 0x01;
                if (target.isSneaking()) entityFlags |= 0x02;
                if (target.isSprinting()) entityFlags |= 0x08;
                if (target.isSwimming()) entityFlags |= 0x10;
                if (target.isInvisible()) entityFlags |= 0x20;
                if (shouldGlow) entityFlags |= 0x40; // Set glowing flag
                if (target.isGliding()) entityFlags |= 0x80;
                
                // Use properly parameterized EntityData constructor
                metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, entityFlags));
                
                // Create the metadata packet correctly
                WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(
                    target.getEntityId(),
                    metadata
                );
                
                PacketEvents.getAPI().getPlayerManager().sendPacket(user, metadataPacket);
            }
        } catch (Exception e) {
            Combat.getInstance().getLogger().warning("Failed to update glowing status: " + e.getMessage());
        }
    }
    
    public void updateGlowingForAll() {
        if (!glowingEnabled || !packetEventsAvailable) return;
        
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (glowingPlayers.contains(target.getUniqueId())) {
                for (Player observer : Bukkit.getOnlinePlayers()) {
                    if (!target.equals(observer)) {
                        updateGlowingForPlayer(target, observer);
                    }
                }
            }
        }
    }

    public void cleanup() {
        if (!glowingEnabled || !packetEventsAvailable) return;
        
        // Remove glowing effect from all players
        for (UUID playerId : new HashSet<>(glowingPlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                setGlowing(player, false);
            }
        }
        
        glowingPlayers.clear();
        entityIdMap.clear();
        
        // Clean up metadata
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.removeMetadata(GLOWING_METADATA_KEY, Combat.getInstance());
        }
    }
}