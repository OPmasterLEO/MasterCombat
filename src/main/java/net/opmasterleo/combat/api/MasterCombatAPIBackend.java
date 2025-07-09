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
            if (plugin.getWorldGuardUtil() != null) {
                Boolean cached = plugin.getWorldGuardUtil().getCachedPvpState(uuid, player.getLocation());
                if (cached != null) {
                    if (cached) return;
                } else {
                    if (plugin.getWorldGuardUtil().isPvpDenied(player)) return;
                }
            }
            plugin.setCombat(player, player);
        }
    }

    @Override
    public void untagPlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            plugin.getCombatPlayers().remove(uuid);
            UUID opponentUUID = plugin.getCombatOpponents().remove(uuid);
            
            if (plugin.getGlowManager() != null) {
                plugin.getGlowManager().setGlowing(player, false);
                
                if (opponentUUID != null) {
                    Player opponent = Bukkit.getPlayer(opponentUUID);
                    if (opponent != null) {
                        plugin.getGlowManager().setGlowing(opponent, false);
                    }
                }
            }
        }
    }
}