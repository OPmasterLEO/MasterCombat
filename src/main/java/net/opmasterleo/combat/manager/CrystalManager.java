package net.opmasterleo.combat.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import net.opmasterleo.combat.Combat;

public final class CrystalManager {
    private final Map<UUID, UUID> endCrystalMap;

    public CrystalManager() {
        this.endCrystalMap = new ConcurrentHashMap<>();
    }

    public Player getPlacer(Entity crystal) {
        if (!Combat.getInstance().getConfig().getBoolean("link-end-crystals", true)) {
            return null;
        }

        UUID entityId = crystal.getUniqueId();
        UUID playerId = this.endCrystalMap.get(entityId);
        if (playerId == null) {
            return null;
        }

        Player placer = Bukkit.getPlayer(playerId);
        if (placer == null) {
            if (!Combat.getInstance().getConfig().getBoolean("self-combat", false)) {
                return null;
            }
        }
        return placer;
    }

    public void setPlacer(Entity crystal, Player player) {
        UUID entityId = crystal.getUniqueId();
        UUID playerId = player.getUniqueId();
        this.endCrystalMap.put(entityId, playerId);
    }

    public void remove(UUID crystalId) {
        this.endCrystalMap.remove(crystalId);
    }

    public void removeCrystal(Entity crystal) {
        this.endCrystalMap.remove(crystal.getUniqueId());
    }
}