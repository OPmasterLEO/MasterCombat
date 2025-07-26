package net.opmasterleo.combat.manager;

import org.bukkit.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityManager {
    private final Map<Integer, Entity> entityMap = new ConcurrentHashMap<>();

    public void trackEntity(Entity entity) {
        entityMap.put(entity.getEntityId(), entity);
    }

    public void untrackEntity(Entity entity) {
        entityMap.remove(entity.getEntityId());
    }

    public Entity getEntity(int entityId) {
        return entityMap.get(entityId);
    }
}