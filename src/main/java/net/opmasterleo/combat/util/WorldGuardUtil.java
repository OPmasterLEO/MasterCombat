package net.opmasterleo.combat.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import net.opmasterleo.combat.Combat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;

public class WorldGuardUtil extends PacketListenerAbstract implements Listener {

    private static boolean packetEventsEnabled = true;

    private final RegionQuery regionQuery;
    private final Map<Long, CacheEntry> pvpCache = new LRUCache<>(1024);
    private static final long CACHE_TIMEOUT = 30000;
    private long lastCleanupTime = System.currentTimeMillis();
    private final Combat plugin;
    private boolean barrierEnabled;
    private Material barrierMaterial;
    private int detectionRadius;
    private int barrierHeight;
    private double pushBackForce;
    private final Map<UUID, Long> lastBarrierWarning = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastBarrierLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Vector3d> lastPositions = new ConcurrentHashMap<>();
    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxSize;

        public LRUCache(int maxSize) {
            super(16, 0.75f, true);
            this.maxSize = maxSize;
        }
        
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxSize;
        }
    }
    
    private static class CacheEntry {
        final boolean pvpDenied;
        final long timestamp;
        
        CacheEntry(boolean denied) {
            this.pvpDenied = denied;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TIMEOUT;
        }
    }

    public WorldGuardUtil(Combat plugin) {
        this.plugin = plugin;

        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            regionQuery = container.createQuery();
            startCleanupTask();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize WorldGuard integration", e);
        }

        reloadConfig();
        PacketEvents.getAPI().getEventManager().registerListener(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }
    
    public void reloadConfig() {
        barrierEnabled = plugin.getConfig().getBoolean("safezone_protection.enabled", true);
        String materialName = plugin.getConfig().getString("safezone_protection.barrier_material", "RED_STAINED_GLASS");
        try {
            barrierMaterial = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            barrierMaterial = Material.RED_STAINED_GLASS;
            plugin.getLogger().warning("Invalid barrier material: " + materialName + ". Using RED_STAINED_GLASS instead.");
        }
        
        detectionRadius = plugin.getConfig().getInt("safezone_protection.barrier_detection_radius", 5);
        barrierHeight = plugin.getConfig().getInt("safezone_protection.barrier_height", 3);
        pushBackForce = plugin.getConfig().getDouble("safezone_protection.push_back_force", 0.6);
        SchedulerUtil.runTaskTimerAsync(plugin, () -> {
            long currentTime = System.currentTimeMillis();
            lastBarrierWarning.entrySet().removeIf(entry -> currentTime - entry.getValue() > 10000);
            lastBarrierLocations.entrySet().removeIf(entry -> 
                plugin.getServer().getPlayer(entry.getKey()) == null || 
                !plugin.isInCombat(plugin.getServer().getPlayer(entry.getKey()))
            );
        }, 100L, 100L);
    }
    
    public static void disablePacketEventsIntegration() {
        packetEventsEnabled = false;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        try {
            if (!plugin.isEnabled() || !plugin.isPluginEnabled()) return;

            if (!packetEventsEnabled || !barrierEnabled) return;
            var type = event.getPacketType();

            if (type == Client.PLAYER_POSITION || type == Client.PLAYER_POSITION_AND_ROTATION) {
                Player player = (Player) event.getPlayer();
                if (player == null || !player.isOnline()) return;
                if (player.hasPermission("combat.bypass.safezone")) return;

                Vector3d newPos;
                if (type == Client.PLAYER_POSITION) {
                    WrapperPlayClientPlayerPosition wrapper = new WrapperPlayClientPlayerPosition(event);
                    newPos = wrapper.getPosition();
                } else {
                    WrapperPlayClientPlayerPositionAndRotation wrapper = new WrapperPlayClientPlayerPositionAndRotation(event);
                    newPos = wrapper.getPosition();
                }

                Vector3d lastPos = lastPositions.get(player.getUniqueId());
                lastPositions.put(player.getUniqueId(), newPos);
                if (lastPos != null && lastPos.getX() == newPos.getX() && lastPos.getZ() == newPos.getZ()) {
                    return;
                }

                Location to = new Location(player.getWorld(), newPos.getX(), newPos.getY(), newPos.getZ());
                Location from = lastPos != null
                    ? new Location(player.getWorld(), lastPos.getX(), lastPos.getY(), lastPos.getZ())
                    : player.getLocation();

                SchedulerUtil.runTask(plugin, () -> handlePlayerMovement(player, from, to));
            }
        } catch (IllegalStateException e) {
            if (plugin.isEnabled()) {
                plugin.debug("Error in WorldGuardUtil.onPacketReceive: " + e.getMessage());
            }
        } catch (Exception e) {
            if (plugin.isEnabled()) {
                plugin.debug("Unexpected error in WorldGuardUtil.onPacketReceive: " + e.getMessage());
            }
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!barrierEnabled) return;

        Player player = event.getPlayer();
        if (!plugin.isInCombat(player)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || to == null) return;

        boolean fromInSafeZone = isPvpDenied(from);
        boolean toInSafeZone = isPvpDenied(to);
        if (!fromInSafeZone && toInSafeZone) {
            event.setCancelled(true);
            player.sendMessage(ChatUtil.parse("&cYou cannot teleport into a safe zone while in combat!"));
        }
    }

    private void handlePlayerMovement(Player player, Location from, Location to) {
        if (isNearSafezone(to)) {
            createVisualBarrier(player, to);
        } else {
            lastBarrierLocations.remove(player.getUniqueId());
        }

        if (plugin.isInCombat(player) && !player.hasPermission("combat.bypass.safezone")) {
            boolean fromInSafe = isPvpDenied(from);
            boolean toInSafe = isPvpDenied(to);
            if (!fromInSafe && toInSafe) {
                pushPlayerBack(player);
            }
        }
    }
    
    private void startCleanupTask() {
        SchedulerUtil.runTaskTimerAsync(plugin, () -> {
            if (System.currentTimeMillis() - lastCleanupTime > 60000) {
                pvpCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
                lastCleanupTime = System.currentTimeMillis();
            }
        }, 1200L, 1200L);
    }
    
    public boolean isPvpDenied(Location location) {
        if (location == null) return false;
        long key = locationToChunkKey(location);
        CacheEntry cached = pvpCache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.pvpDenied;
        }

        boolean denied;
        try {
            ApplicableRegionSet regions = regionQuery.getApplicableRegions(BukkitAdapter.adapt(location));
            denied = regions.queryValue(null, Flags.PVP) == StateFlag.State.DENY;
        } catch (Exception e) {
            denied = false;
        }

        pvpCache.put(key, new CacheEntry(denied));
        return denied;
    }

    private long locationToChunkKey(Location loc) {
        final int GRID_SIZE_BITS = 4;
        int chunkX = loc.getBlockX() >> GRID_SIZE_BITS;
        int chunkZ = loc.getBlockZ() >> GRID_SIZE_BITS;
        int worldId = loc.getWorld().getUID().hashCode();
        return ((long)worldId << 40) | ((long)chunkX << 20) | (long)chunkZ;
    }
    
    private boolean isNearSafezone(Location location) {
        for (int x = -detectionRadius; x <= detectionRadius; x++) {
            for (int z = -detectionRadius; z <= detectionRadius; z++) {
                Location check = location.clone().add(x, 0, z);
                if (isPvpDenied(check) != isPvpDenied(location)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private void createVisualBarrier(Player player, Location location) {
        Location lastLoc = lastBarrierLocations.get(player.getUniqueId());
        if (lastLoc != null && lastLoc.distanceSquared(location) < 9) {
            return;
        }
        
        lastBarrierLocations.put(player.getUniqueId(), location.clone());
        for (int x = -detectionRadius; x <= detectionRadius; x++) {
            for (int z = -detectionRadius; z <= detectionRadius; z++) {
                Location check = location.clone().add(x, 0, z);
                checkBorder(player, check, BlockFace.EAST);
                checkBorder(player, check, BlockFace.SOUTH);
            }
        }
    }
    
    private void checkBorder(Player player, Location start, BlockFace direction) {
        Location adjacent = start.clone().add(direction.getModX(), 0, direction.getModZ());
        if (isPvpDenied(start) != isPvpDenied(adjacent)) {
            createBarrierLine(player, start, direction);
        }
    }
    
    private void createBarrierLine(Player player, Location start, BlockFace direction) {
        for (int y = 0; y < barrierHeight; y++) {
            Location blockLoc = start.clone().add(0, y, 0);
            sendBlockChange(player, blockLoc, barrierMaterial);
            
            SchedulerUtil.runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    resetBlockChange(player, blockLoc);
                }
            }, 100L);
        }
    }
    
    private void sendBlockChange(Player player, Location loc, Material material) {
        try {
            StateType stateType = StateTypes.getByName(material.name());
            if (stateType == null) {
                stateType = StateTypes.RED_STAINED_GLASS;
            }

            Object pkt = null;
            try {
                pkt = Combat.createWrapperPlayServerBlockChange(
                    new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                    Integer.valueOf(stateType.hashCode())
                );
            } catch (Throwable ignored) {}

            if (pkt != null) {
                try {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, pkt);
                } catch (Throwable t) {
                    try {
                        com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange legacy =
                            new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange(
                                new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                                stateType.hashCode()
                            );
                        PacketEvents.getAPI().getPlayerManager().sendPacket(player, legacy);
                    } catch (Throwable ignored) {}
                }
            } else {
                try {
                    com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange legacy =
                        new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange(
                            new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                            stateType.hashCode()
                        );
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, legacy);
                } catch (Throwable ignored) {}
            }
        } catch (Exception e) {
            player.sendBlockChange(loc, material.createBlockData());
        }
    }

    private void resetBlockChange(Player player, Location loc) {
        try {
            StateType stateType = StateTypes.getByName(loc.getBlock().getType().name());
            if (stateType == null) {
                stateType = StateTypes.AIR;
            }

            WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(
                new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()),
                stateType.hashCode()
            );

            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        } catch (Exception e) {
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }
    
    private void pushPlayerBack(Player player) {
        long now = System.currentTimeMillis();
        Long lastWarning = lastBarrierWarning.get(player.getUniqueId());
        if (lastWarning != null && now - lastWarning < 1000) {
            return;
        }
        
        lastBarrierWarning.put(player.getUniqueId(), now);
        
        Location playerLoc = player.getLocation();
        Vector pushVector = findEscapeDirection(playerLoc);
        
        if (!pushVector.isZero()) {
            pushVector.multiply(pushBackForce);
            pushVector.setY(0.2);
            player.setVelocity(pushVector);
            player.sendMessage(ChatUtil.parse("&cYou cannot enter safe zones while in combat!"));
        }
    }
    
    private Vector findEscapeDirection(Location playerLoc) {
        for (int radius = 1; radius <= detectionRadius * 2; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) continue;
                    
                    Location check = playerLoc.clone().add(x, 0, z);
                    if (!isPvpDenied(check)) {
                        return new Vector(x, 0, z).normalize();
                    }
                }
            }
        }
        return new Vector();
    }
}