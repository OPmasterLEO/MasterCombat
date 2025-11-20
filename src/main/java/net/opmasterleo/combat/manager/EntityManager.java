package net.opmasterleo.combat.manager;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.opmasterleo.combat.Combat;

public class EntityManager {
    
    private final ConcurrentHashMap<Integer, EntityCacheEntry> entityCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Player> playerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<World, Long> worldUpdateTimes = new ConcurrentHashMap<>();
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);
    private static final long CACHE_EXPIRE_TIME = 5000;
    private static final long WORLD_UPDATE_INTERVAL = 2000;
    private static final int PLAYER_CACHE_SIZE = 256;
    
    private static class EntityCacheEntry {
        final Entity entity;
        final long timestamp;
        final int worldHash;
        
        EntityCacheEntry(Entity entity) {
            this.entity = entity;
            this.timestamp = System.currentTimeMillis();
            this.worldHash = entity.getWorld().hashCode();
        }
        
        boolean isValid() {
            return entity != null && 
                   entity.isValid() && 
                   !entity.isDead() && 
                   entity.getWorld().hashCode() == worldHash &&
                   System.currentTimeMillis() - timestamp < CACHE_EXPIRE_TIME;
        }
    }
    
    public EntityManager() {
        refreshPlayerCache();
    }
    
    private void refreshPlayerCache() {
        if (playerCache.size() > PLAYER_CACHE_SIZE) {
            playerCache.clear();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            int eid = player.getEntityId();
            playerCache.put(eid, player);
            entityCache.put(eid, new EntityCacheEntry(player));
        }
    }
    
    public void trackPlayer(Player player) {
        if (player != null && player.isOnline()) {
            int eid = player.getEntityId();
            playerCache.put(eid, player);
            entityCache.put(eid, new EntityCacheEntry(player));
        }
    }
    
    public void untrackPlayer(Player player) {
        if (player != null) {
            int eid = player.getEntityId();
            playerCache.remove(eid);
            entityCache.remove(eid);
        }
    }
    
    public Entity getEntity(int entityId) {
        Player cachedPlayer = playerCache.get(entityId);
        if (cachedPlayer != null && cachedPlayer.isOnline()) {
            cacheHits.incrementAndGet();
            return cachedPlayer;
        }
        
        EntityCacheEntry cached = entityCache.get(entityId);
        if (cached != null) {
            if (cached.isValid()) {
                cacheHits.incrementAndGet();
                return cached.entity;
            } else {
                entityCache.remove(entityId);
            }
        }
        cacheMisses.incrementAndGet();
        Entity found = findEntityInWorlds(entityId);
        if (found != null) {
            entityCache.put(entityId, new EntityCacheEntry(found));
            if (found instanceof Player player) {
                playerCache.put(entityId, player);
            }
        }
        return found;
    }
    
    private Entity findEntityInWorlds(int entityId) {
        long now = System.currentTimeMillis();
        List<World> worlds = Bukkit.getWorlds();
        
        for (World world : worlds) {
            Long lastUpdate = worldUpdateTimes.get(world);
            boolean needsFullScan = lastUpdate == null || now - lastUpdate > WORLD_UPDATE_INTERVAL;
            
            if (needsFullScan) {
                List<Entity> entities = world.getEntities();
                for (Entity entity : entities) {
                    if (!entity.isValid() || entity.isDead()) continue;
                    
                    int eid = entity.getEntityId();
                    entityCache.put(eid, new EntityCacheEntry(entity));
                    if (entity instanceof Player player) {
                        playerCache.put(eid, player);
                    }
                    
                    if (eid == entityId) {
                        worldUpdateTimes.put(world, now);
                        return entity;
                    }
                }
                worldUpdateTimes.put(world, now);
            } else {
                for (Entity entity : world.getEntities()) {
                    if (entity.getEntityId() == entityId && entity.isValid() && !entity.isDead()) {
                        return entity;
                    }
                }
            }
        }
        
        return null;
    }
    
    public void trackEntity(Entity entity) {
        if (entity != null && entity.isValid()) {
            entityCache.put(entity.getEntityId(), new EntityCacheEntry(entity));
        }
    }
    
    public void untrackEntity(Entity entity) {
        if (entity != null) {
            entityCache.remove(entity.getEntityId());
        }
    }
    
    public void cleanup() {
        entityCache.values().removeIf(entry -> !entry.isValid());
        playerCache.entrySet().removeIf(entry -> !entry.getValue().isOnline());
        worldUpdateTimes.clear();
        int hits = cacheHits.get();
        int misses = cacheMisses.get();
        if (hits + misses > 1000) {
            double hitRate = hits * 100.0 / (hits + misses);
            Combat.getInstance().debug(String.format("Entity cache performance: %.2f%% hit rate (%d hits, %d misses, %d players)", 
                                                   hitRate, hits, misses, playerCache.size()));
            cacheHits.set(0);
            cacheMisses.set(0);
        }
    }
}