package net.opmasterleo.combat.manager;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CrystalManager {
    private final ConcurrentHashMap<UUID, UUID> crystalPlacers = new ConcurrentHashMap<>();

    public void initialize(Combat plugin) {
        SchedulerUtil.runTaskTimerAsync(plugin, this::cleanupExpiredEntries, 20 * 60, 20 * 60);
    }

    public void setPlacer(Entity crystal, Player placer) {
        if (crystal == null || placer == null) return;
        crystalPlacers.put(crystal.getUniqueId(), placer.getUniqueId());
    }

    public Player getPlacer(Entity crystal) {
        if (crystal == null) return null;
        UUID placerUuid = crystalPlacers.get(crystal.getUniqueId());
        return placerUuid != null ? Bukkit.getPlayer(placerUuid) : null;
    }

    public void removeCrystal(Entity crystal) {
        if (crystal == null) return;
        crystalPlacers.remove(crystal.getUniqueId());
    }
    
    private void cleanupExpiredEntries() {
        if (crystalPlacers.isEmpty()) return;
        
        Set<UUID> toRemove = ConcurrentHashMap.newKeySet();
        for (UUID crystalUuid : crystalPlacers.keySet()) {
            Entity entity = null;
            for (World world : Bukkit.getWorlds()) {
                entity = getEntityByUUID(world, crystalUuid);
                if (entity != null) break;
            }

            if (entity == null || !entity.isValid() || entity.isDead()) {
                toRemove.add(crystalUuid);
            }
        }
        
        for (UUID uuid : toRemove) {
            crystalPlacers.remove(uuid);
        }
        
        if (!toRemove.isEmpty() && Combat.getInstance().isDebugEnabled()) {
            Combat.getInstance().debug("Cleaned up " + toRemove.size() + " expired crystal entries");
        }
    }

    private Entity getEntityByUUID(World world, UUID uuid) {
        for (Entity entity : world.getEntities()) {
            if (entity.getUniqueId().equals(uuid)) {
                return entity;
            }
        }
        return null;
    }
}