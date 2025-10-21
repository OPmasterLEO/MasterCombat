package net.opmasterleo.combat.manager;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class CrystalManager {
    private static final long CLEANUP_INTERVAL = TimeUnit.MINUTES.toMillis(1);
    private static final long CRYSTAL_EXPIRY = TimeUnit.MINUTES.toMillis(5);
    private long lastCleanup = System.currentTimeMillis();
    private final Combat plugin;

    private static class CrystalData {
        final UUID placerUUID;
        final long timestamp;
        final String worldName;

        CrystalData(UUID placerUUID, String worldName) {
            this.placerUUID = placerUUID;
            this.timestamp = System.currentTimeMillis();
            this.worldName = worldName;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CRYSTAL_EXPIRY;
        }
    }

    private final Map<UUID, CrystalData> crystalData = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> playerCrystals = new ConcurrentHashMap<>();

    public CrystalManager() {
        this.plugin = Combat.getInstance();
    }

    public void initialize(Combat plugin) {
        SchedulerUtil.runTaskTimerAsync(plugin, () -> {
            try {
                cleanupExpiredEntries();
            } catch (Exception e) {
                plugin.debug("Error during crystal cleanup: " + e.getMessage());
            }
        }, 1200, 1200);
    }

    public void setPlacer(Entity crystal, Player placer) {
        if (crystal == null || placer == null || !(crystal instanceof EnderCrystal)) return;

        UUID crystalId = crystal.getUniqueId();
        UUID placerId = placer.getUniqueId();

        crystalData.computeIfAbsent(crystalId, k -> new CrystalData(placerId, crystal.getWorld().getName()));
        playerCrystals.computeIfAbsent(placerId, k -> ConcurrentHashMap.newKeySet()).add(crystalId);

        if (System.currentTimeMillis() - lastCleanup > CLEANUP_INTERVAL) {
            plugin.getCombatWorkerPool().execute(this::cleanupExpiredEntries);
        }
    }

    public Player getPlacer(Entity crystal) {
        if (crystal == null) return null;

        CrystalData data = crystalData.get(crystal.getUniqueId());
        if (data == null) return null;

        if (data.isExpired()) {
            removeCrystal(crystal);
            return null;
        }

        return Bukkit.getPlayer(data.placerUUID);
    }

    public void removeCrystal(Entity crystal) {
        if (crystal == null) return;

        UUID crystalId = crystal.getUniqueId();
        CrystalData data = crystalData.remove(crystalId);

        if (data != null) {
            Set<UUID> playerSet = playerCrystals.get(data.placerUUID);
            if (playerSet != null) {
                playerSet.remove(crystalId);
                if (playerSet.isEmpty()) {
                    playerCrystals.remove(data.placerUUID);
                }
            }
        }
    }

    private void cleanupExpiredEntries() {
        if (crystalData.isEmpty()) return;
        lastCleanup = System.currentTimeMillis();

        final Map<String, World> worldCache = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            worldCache.put(world.getName(), world);
        }

        // Schedule the cleanup task on the main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            crystalData.keySet().removeIf(crystalId -> {
                CrystalData data = crystalData.get(crystalId);
                if (data == null || data.isExpired()) return true;
                World world = worldCache.get(data.worldName);
                if (world == null) return true;
                Entity entity = world.getEntity(crystalId);
                return entity == null || !entity.isValid() || entity.isDead();
            });

            playerCrystals.forEach((placerId, playerSet) -> playerSet.removeIf(crystalId -> !crystalData.containsKey(crystalId)));
            playerCrystals.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            if (plugin.isDebugEnabled()) {
                plugin.debug("Cleaned up expired crystal entries");
            }
        });
    }
}