package net.opmasterleo.combat.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.opmasterleo.combat.Combat;

public final class CrystalManager {
    private final Map<Integer, UUID> crystalEntityMap = new ConcurrentHashMap<>(2048);
    private final Map<Integer, Long> expiryTimes = new ConcurrentHashMap<>(2048);
    private static final long CRYSTAL_TTL = 300000;

    public CrystalManager() {
        startCleanupTask();
    }
    
    private void startCleanupTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(Combat.getInstance(), () -> {
            final long[] lastCleanupTime = {System.currentTimeMillis()};
            if (System.currentTimeMillis() - lastCleanupTime[0] > 60000) {
                long now = System.currentTimeMillis();
                expiryTimes.entrySet().removeIf(entry -> entry.getValue() < now);
                crystalEntityMap.keySet().removeIf(id -> !expiryTimes.containsKey(id));
                lastCleanupTime[0] = now;
            }
        }, 1200L, 1200L);
    }

    public Player getPlacer(Entity crystal) {
        if (crystal == null) return null;
        
        if (!Combat.getInstance().getConfig().getBoolean("link-end-crystals", true)) {
            return null;
        }

        UUID playerId = crystalEntityMap.get(crystal.getEntityId());
        if (playerId == null) return null;

        return Bukkit.getPlayer(playerId);
    }

    public void setPlacer(Entity crystal, Player player) {
        if (crystal == null || player == null) return;
        
        int entityId = crystal.getEntityId();
        crystalEntityMap.put(entityId, player.getUniqueId());
        expiryTimes.put(entityId, System.currentTimeMillis() + CRYSTAL_TTL);
    }

    public void removeCrystal(Entity crystal) {
        if (crystal == null) return;
        
        int entityId = crystal.getEntityId();
        crystalEntityMap.remove(entityId);
        expiryTimes.remove(entityId);
    }
}