package net.opmasterleo.combat.manager;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

public class WorldGuardUtil {
    private final RegionQuery regionQuery;
    private final Map<Long, CacheEntry> pvpCache = new LRUCache<>(1024); // Use LRU cache with limited size
    private static final long CACHE_TIMEOUT = 30000; // Increased cache time to 30s
    private long lastCleanupTime = System.currentTimeMillis();
    
    // LRU Cache implementation to limit memory usage
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

    public WorldGuardUtil() {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        regionQuery = container.createQuery();
        startCleanupTask();
    }
    
    private void startCleanupTask() {
        net.opmasterleo.combat.util.SchedulerUtil.runTaskTimerAsync(
            net.opmasterleo.combat.Combat.getInstance(), 
            () -> {
                if (System.currentTimeMillis() - lastCleanupTime > 60000) {
                    pvpCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
                    lastCleanupTime = System.currentTimeMillis();
                }
            }, 1200L, 1200L);
    }
    public boolean isPvpDenied(Player player) {
        if (player == null) return false;
        Location location = player.getLocation();
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

    public Boolean getCachedPvpState(UUID playerUuid, Location location) {
        long key = locationToChunkKey(location);
        CacheEntry cached = pvpCache.get(key);
        
        if (cached != null && !cached.isExpired()) {
            return cached.pvpDenied;
        }
        
        return null;
    }
}