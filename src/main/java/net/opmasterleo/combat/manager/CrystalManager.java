package net.opmasterleo.combat.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.opmasterleo.combat.Combat;

public final class CrystalManager {
    private static final long CRYSTAL_TTL = TimeUnit.SECONDS.toMillis(60);
    private static final long CLEANUP_INTERVAL = TimeUnit.SECONDS.toMillis(30);
    
    private static class CrystalData {
        final UUID placer;
        final long expiry;
        
        CrystalData(UUID placer, long expiry) {
            this.placer = placer;
            this.expiry = expiry;
        }
    }

    private final Map<Integer, CrystalData> crystalMap = new ConcurrentHashMap<>(512);
    private volatile long nextCleanupTime = System.currentTimeMillis() + CLEANUP_INTERVAL;

    public CrystalManager() {
        startCleanupTask();
    }
    
    private void startCleanupTask() {
        net.opmasterleo.combat.util.SchedulerUtil.runTaskTimerAsync(
            Combat.getInstance(),
            this::cleanExpiredCrystals,
            600L, 600L);
    }

    private void cleanExpiredCrystals() {
        long currentTime = System.currentTimeMillis();
        if (currentTime < nextCleanupTime) return;
        
        long newNextCleanup = currentTime + CLEANUP_INTERVAL;
        crystalMap.entrySet().removeIf(entry -> {
            if (entry.getValue().expiry < currentTime) {
                return true;
            }
            return false;
        });
        nextCleanupTime = newNextCleanup;
    }

    public Player getPlacer(Entity crystal) {
        if (crystal == null) return null;
        
        if (!Combat.getInstance().getConfig().getBoolean("link-end-crystals", true)) {
            return null;
        }

        CrystalData data = crystalMap.get(crystal.getEntityId());
        return data != null ? Bukkit.getPlayer(data.placer) : null;
    }

    public void setPlacer(Entity crystal, Player player) {
        if (crystal == null || player == null) return;
        
        int entityId = crystal.getEntityId();
        long expiry = System.currentTimeMillis() + CRYSTAL_TTL;
        crystalMap.put(entityId, new CrystalData(player.getUniqueId(), expiry));
    }

    public void removeCrystal(Entity crystal) {
        if (crystal != null) {
            crystalMap.remove(crystal.getEntityId());
        }
    }
}