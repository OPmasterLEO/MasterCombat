package net.opmasterleo.combat.manager;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.opmasterleo.combat.Combat;

public final class CrystalManager {
    private final Map<Integer, UUID> crystalEntityMap = new ConcurrentHashMap<>(512);
    private final Map<Integer, Long> expiryTimes = new ConcurrentHashMap<>(512);
    private static final long CRYSTAL_TTL = 60000;
    private long lastCleanupTime = System.currentTimeMillis();
    private static final long CLEANUP_INTERVAL = 30000;

    public CrystalManager() {
        startCleanupTask();
    }
    
    private void startCleanupTask() {
        net.opmasterleo.combat.util.SchedulerUtil.runTaskTimerAsync(
            net.opmasterleo.combat.Combat.getInstance(),
            () -> {
                long now = System.currentTimeMillis();
                if (now - lastCleanupTime > CLEANUP_INTERVAL) {
                    int removed = 0;
                    int batchSize = 100;
                    Set<Integer> expiredKeys = new HashSet<>();
                    for (Map.Entry<Integer, Long> entry : expiryTimes.entrySet()) {
                        if (entry.getValue() < now) {
                            expiredKeys.add(entry.getKey());
                            removed++;
                        }
                        
                        if (removed >= batchSize) break;
                    }

                    for (Integer key : expiredKeys) {
                        expiryTimes.remove(key);
                        crystalEntityMap.remove(key);
                    }
                    
                    lastCleanupTime = now;
                }
            }, 600L, 600L);
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