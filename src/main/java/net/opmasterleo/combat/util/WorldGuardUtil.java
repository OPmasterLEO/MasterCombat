package net.opmasterleo.combat.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import net.opmasterleo.combat.Combat;

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
    private boolean bypassEnabled;
    private boolean opBypass;
    private String bypassPermission;
    private final Map<UUID, Long> lastBarrierWarning = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastBarrierLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Vector3d> lastPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBarrierRender = new ConcurrentHashMap<>();
    private static final long BARRIER_RENDER_INTERVAL_MS = 100L;
    private final java.util.concurrent.atomic.AtomicLong wgPackets = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong exitNotInCombat = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong exitBypass = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong exitWorldDisabled = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong exitSmallDelta = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong processedPackets = new java.util.concurrent.atomic.AtomicLong();
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
    }

    public void initialize(Combat plugin) {
        if (plugin == null) return;
        reloadConfig();
        
        try {
            if (plugin.isPacketEventsAvailable() && PacketEvents.getAPI() != null) {
                PacketEvents.getAPI().getEventManager().registerListener(this);
            }
        } catch (Exception ignored) {}

        try {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        } catch (Exception ignored) {}
    }
    
    public void reloadConfig() {
        barrierEnabled = plugin.getConfig().getBoolean("safezone_protection.enabled", true);
        String materialName = plugin.getConfig().getString("safezone_protection.barrier_material", "RED_STAINED_GLASS");
        if (materialName == null || materialName.isBlank()) {
            barrierMaterial = Material.RED_STAINED_GLASS;
        } else {
            try {
                barrierMaterial = Material.valueOf(materialName.toUpperCase());
            } catch (IllegalArgumentException e) {
                barrierMaterial = Material.RED_STAINED_GLASS;
                plugin.getLogger().warning(String.format("Invalid barrier material: %s. Using RED_STAINED_GLASS instead.", materialName));
            }
        }
 
        detectionRadius = plugin.getConfig().getInt("safezone_protection.barrier_detection_radius", 5);
        barrierHeight = plugin.getConfig().getInt("safezone_protection.barrier_height", 3);
        pushBackForce = plugin.getConfig().getDouble("safezone_protection.push_back_force", 0.6);
    bypassEnabled = plugin.getConfig().getBoolean("safezone_protection.bypass.enabled", true);
    opBypass = plugin.getConfig().getBoolean("safezone_protection.bypass.op_bypass", false);
    String defPerm = "combat.bypass.safezone";
    String cfgPerm = plugin.getConfig().getString("safezone_protection.bypass.permission", defPerm);
    bypassPermission = (cfgPerm == null || cfgPerm.isBlank()) ? defPerm : cfgPerm;
        SchedulerUtil.runTaskTimerAsync(plugin, () -> {
            long currentTime = System.currentTimeMillis();
            lastBarrierWarning.entrySet().removeIf(entry -> currentTime - entry.getValue() > 10000);
            lastBarrierLocations.entrySet().removeIf(entry -> {
                Player p = plugin.getServer().getPlayer(entry.getKey());
                return p == null || !plugin.isInCombat(p);
            });
            lastBarrierRender.entrySet().removeIf(entry -> currentTime - entry.getValue() > 10000);
        }, 60L, 60L);
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
                boolean dbg = plugin.isDebugEnabled();
                if (dbg) wgPackets.incrementAndGet();
                Player player = (Player) event.getPlayer();
                if (!player.isOnline()) return;
                if (!plugin.isInCombat(player)) { if (dbg) exitNotInCombat.incrementAndGet(); return; }
                if (shouldBypass(player)) { if (dbg) exitBypass.incrementAndGet(); return; }
                if (!plugin.isCombatEnabledInWorld(player)) { if (dbg) exitWorldDisabled.incrementAndGet(); return; }
                Vector3d newPos;
                if (type == Client.PLAYER_POSITION) {
                    WrapperPlayClientPlayerPosition wrapper = new WrapperPlayClientPlayerPosition(event);
                    newPos = wrapper.getPosition();
                } else {
                    WrapperPlayClientPlayerPositionAndRotation wrapper = new WrapperPlayClientPlayerPositionAndRotation(event);
                    newPos = wrapper.getPosition();
                }

                Vector3d lastPos = lastPositions.get(player.getUniqueId());
                if (lastPos != null) {
                    double deltaX = Math.abs(newPos.getX() - lastPos.getX());
                    double deltaZ = Math.abs(newPos.getZ() - lastPos.getZ());
                    if (deltaX < 0.01 && deltaZ < 0.01) { if (dbg) exitSmallDelta.incrementAndGet(); return; }
                }
                
                lastPositions.put(player.getUniqueId(), newPos);
                Location to = new Location(player.getWorld(), newPos.getX(), newPos.getY(), newPos.getZ());
                Location from = lastPos != null
                    ? new Location(player.getWorld(), lastPos.getX(), lastPos.getY(), lastPos.getZ())
                    : player.getLocation();
                if (dbg) processedPackets.incrementAndGet();
                SchedulerUtil.runEntityTask(plugin, player, () -> handlePlayerMovement(player, from, to));
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
        if (!plugin.isInCombat(player) || shouldBypass(player)) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        boolean fromInSafeZone = isPvpDenied(from);
        boolean toInSafeZone = isPvpDenied(to);
        if (!fromInSafeZone && toInSafeZone) {
            event.setCancelled(true);
            player.sendMessage(ChatUtil.parse("&cYou cannot teleport into a safe zone while in combat!"));
        }
    }

    private void handlePlayerMovement(Player player, Location from, Location to) {
        boolean inCombat = plugin.isInCombat(player);
        
        if (inCombat && isNearSafezone(to)) {
            createVisualBarrier(player, to);
        } else {
            lastBarrierLocations.remove(player.getUniqueId());
        }

        if (inCombat && !shouldBypass(player)) {
            boolean fromInSafe = isPvpDenied(from);
            if (!fromInSafe) {
                boolean toInSafe = isPvpDenied(to);
                if (toInSafe) {
                    pushPlayerBack(player);
                }
            }
        }
    }
    
    private void startCleanupTask() {
        SchedulerUtil.runTaskTimerAsync(plugin, () -> {
            if (System.currentTimeMillis() - lastCleanupTime > 60000) {
                pvpCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
                lastCleanupTime = System.currentTimeMillis();
                if (plugin.isDebugEnabled()) {
                    long total = wgPackets.getAndSet(0);
                    long notCombat = exitNotInCombat.getAndSet(0);
                    long bypass = exitBypass.getAndSet(0);
                    long worldOff = exitWorldDisabled.getAndSet(0);
                    long small = exitSmallDelta.getAndSet(0);
                    long processed = processedPackets.getAndSet(0);
                    long exits = notCombat + bypass + worldOff + small;
                    double exitRate = total > 0 ? (exits * 100.0 / total) : 0.0;
                    plugin.debug(String.format(
                        "WG packets: total=%d, exits={notCombat=%d, bypass=%d, worldDisabled=%d, smallDelta=%d} processed=%d, exitRate=%.1f%%",
                        total, notCombat, bypass, worldOff, small, processed, exitRate));
                }
            }
        }, 600L, 600L);
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
        if (loc == null || loc.getWorld() == null) return 0L;
        final int GRID_SIZE_BITS = 4;
        int chunkX = loc.getBlockX() >> GRID_SIZE_BITS;
        int chunkZ = loc.getBlockZ() >> GRID_SIZE_BITS;
        int worldId = loc.getWorld().getUID().hashCode();
        return ((long)worldId << 40) | ((long)chunkX << 20) | (long)chunkZ;
    }
    
    private boolean isNearSafezone(Location location) {
        boolean base = isPvpDenied(location);
        Location checkLoc = location.clone();
        for (int i = 1; i <= detectionRadius; i++) {
            checkLoc.setX(location.getX() + i);
            if (isPvpDenied(checkLoc) != base) return true;
            checkLoc.setX(location.getX() - i);
            if (isPvpDenied(checkLoc) != base) return true;
            checkLoc.setX(location.getX());
            checkLoc.setZ(location.getZ() + i);
            if (isPvpDenied(checkLoc) != base) return true;
            checkLoc.setZ(location.getZ() - i);
            if (isPvpDenied(checkLoc) != base) return true;
            checkLoc.setZ(location.getZ());
        }
        return false;
    }
    
    private void createVisualBarrier(Player player, Location location) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastTime = lastBarrierRender.get(id);
        if (lastTime != null && (now - lastTime) < BARRIER_RENDER_INTERVAL_MS) return;
        Location lastLoc = lastBarrierLocations.get(id);
        if (lastLoc != null && lastLoc.getWorld() == location.getWorld() && lastLoc.distanceSquared(location) < 9) {
            return;
        }

        lastBarrierRender.put(id, now);
        lastBarrierLocations.put(id, location.clone());

        boolean base = isPvpDenied(location);
        Location east = findBorder(location, 1, 0, base);
        Location west = findBorder(location, -1, 0, base);
        Location south = findBorder(location, 0, 1, base);
        Location north = findBorder(location, 0, -1, base);

        java.util.List<Location> starts = new java.util.ArrayList<>(4);
        if (east != null) starts.add(east);
        if (west != null) starts.add(west);
        if (south != null) starts.add(south);
        if (north != null) starts.add(north);
        if (!starts.isEmpty()) {
            createBarrierGroup(player, starts);
        }
    }

    private Location findBorder(Location origin, int dx, int dz, boolean base) {
        Location cursor = origin.clone();
        double startX = origin.getX();
        double startZ = origin.getZ();
        for (int i = 1; i <= detectionRadius; i++) {
            cursor.setX(startX + dx * i);
            cursor.setZ(startZ + dz * i);
            boolean state = isPvpDenied(cursor);
            if (state != base) {
                cursor.setX(startX + dx * (i - 1));
                cursor.setZ(startZ + dz * (i - 1));
                return cursor.clone();
            }
        }
        return null;
    }

    private void createBarrierGroup(Player player, java.util.List<Location> starts) {
        if (starts == null || starts.isEmpty()) return;
        final int maxHeight = Math.min(barrierHeight, 5);
        for (Location base : starts) {
            if (base == null) continue;
            final double baseY = base.getY();
            Location temp = base.clone();
            for (int y = 0; y < maxHeight; y++) {
                temp.setY(baseY + y);
                sendBlockChange(player, temp, barrierMaterial);
            }
        }

        Location anchor = starts.get(0);
        SchedulerUtil.runRegionTaskLater(plugin, anchor, () -> {
            if (!player.isOnline()) return;
            for (Location base : starts) {
                if (base == null) continue;
                final double baseY = base.getY();
                Location temp = base.clone();
                for (int y = 0; y < maxHeight; y++) {
                    temp.setY(baseY + y);
                    resetBlockChange(player, temp);
                }
            }
        }, 60L);
    }
    
    private void sendBlockChange(Player player, Location loc, Material material) {
        try {
            player.sendBlockChange(loc, material.createBlockData());
        } catch (Throwable ignored) {
        }
    }

    private void resetBlockChange(Player player, Location loc) {
        try {
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!barrierEnabled) return;
        Player player = event.getPlayer();
        if (!player.isOnline()) return;
        if (shouldBypass(player)) return;
        boolean inCombat = plugin.isInCombat(player);
        if (!inCombat) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        if (isNearSafezone(to)) {
            createVisualBarrier(player, to);
        }

        if (!shouldBypass(player)) {
            boolean fromSafe = isPvpDenied(from);
            boolean toSafe = isPvpDenied(to);
            if (!fromSafe && toSafe) {
                event.setTo(from);
                pushPlayerBack(player);
            }
        }
    }

    private boolean shouldBypass(Player player) {
        if (!bypassEnabled) return false;
        if (opBypass && player.isOp()) return true;
        return bypassPermission != null && !bypassPermission.isEmpty() && player.hasPermission(bypassPermission);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastBarrierWarning.remove(id);
        lastBarrierLocations.remove(id);
        lastPositions.remove(id);
    }
    
    private void pushPlayerBack(Player player) {
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastWarning = lastBarrierWarning.get(playerId);
        if (lastWarning != null && now - lastWarning < 1000) {
            return;
        }
        
        lastBarrierWarning.put(playerId, now);
        
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
        Location check = playerLoc.clone();
        int maxRadius = detectionRadius * 2;
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) continue;
                    
                    check.setX(playerLoc.getX() + x);
                    check.setZ(playerLoc.getZ() + z);
                    if (!isPvpDenied(check)) {
                        return new Vector(x, 0, z).normalize();
                    }
                }
            }
        }
        return new Vector();
    }
}