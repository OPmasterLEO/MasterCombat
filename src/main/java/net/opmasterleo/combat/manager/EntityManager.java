package net.opmasterleo.combat.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityManager {
    
    private final Map<Integer, Entity> entityCache = new ConcurrentHashMap<>();
    
    public EntityManager() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            entityCache.put(player.getEntityId(), player);
        }
    }
    
    public Entity getEntity(int entityId) {
        Entity cached = entityCache.get(entityId);
        if (cached != null && cached.isValid()) {
            return cached;
        }
        
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getEntityId() == entityId) {
                    entityCache.put(entityId, entity);
                    return entity;
                }
            }
        }
        
        entityCache.remove(entityId);
        return null;
    }
    
    public void trackEntity(Entity entity) {
        if (entity != null) {
            entityCache.put(entity.getEntityId(), entity);
        }
    }
    
    public void untrackEntity(Entity entity) {
        if (entity != null) {
            entityCache.remove(entity.getEntityId());
        }
    }
    
    public void cleanup() {
        entityCache.clear();
    }
}