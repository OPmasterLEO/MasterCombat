package net.opmasterleo.combat.manager;

import org.bukkit.entity.Player;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import lombok.Getter;

@Getter
public class WorldGuardUtil {

    private final RegionQuery regionQuery;

    public WorldGuardUtil() {
        regionQuery = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
    }

    public boolean isPvpDenied(Player player) {
        ApplicableRegionSet applicableRegions = regionQuery.getApplicableRegions(BukkitAdapter.adapt(player.getLocation()));
        return applicableRegions.getRegions().stream().anyMatch(region -> region.getFlag(Flags.PVP) == StateFlag.State.DENY);
    }
}