package net.opmasterleo.combat.api;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.opmasterleo.combat.Combat;

public class MasterCombatAPIBackend implements MasterCombatAPI {

    private final Combat plugin;

    public MasterCombatAPIBackend(Combat plugin) {
        this.plugin = plugin;
    }

    @Override
    public void tagPlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            if (!plugin.isCombatEnabled()) {
                plugin.debug("API call ignored: combat disabled in config");
                return;
            }
            
            if (!plugin.isCombatEnabledInWorld(player)) {
                plugin.debug("API call ignored: world disabled for combat");
                return;
            }
            
            if (plugin.getWorldGuardUtil() != null && plugin.getWorldGuardUtil().isPvpDenied(player.getLocation())) {
                plugin.debug("API call ignored: player in protected region");
                return;
            }
            
            plugin.setCombat(player, player);
        }
    }

    @Override
    public void untagPlayer(UUID uuid) {
        if (uuid == null) return;
        
        Player player = Bukkit.getPlayer(uuid);
        Combat.CombatRecord record = plugin.getCombatRecords().remove(uuid);
        
        if (player != null && plugin.getGlowManager() != null) {
            plugin.getGlowManager().setGlowing(player, false);
        }
        
        if (record != null && record.opponent != null) {
            Player opponent = Bukkit.getPlayer(record.opponent);
            if (opponent != null && plugin.getGlowManager() != null) {
                boolean stillInCombat = plugin.getCombatRecords().containsKey(record.opponent);
                if (!stillInCombat) {
                    plugin.getGlowManager().setGlowing(opponent, false);
                }
            }
        }
    }
}