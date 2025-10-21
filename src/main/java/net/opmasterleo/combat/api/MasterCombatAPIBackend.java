package net.opmasterleo.combat.api;

import java.lang.reflect.Method;
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

    @Override
    public String getMasterCombatState(UUID uuid) {
        if (uuid == null) return "Idle";
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return "Idle";
        return plugin.isInCombat(player) ? "Fighting" : "Idle";
    }

    @Override
    public boolean isPlayerGlowing(UUID uuid) {
        if (uuid == null) return false;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return false;

        Object glowManager = plugin.getGlowManager();
        if (glowManager == null) return false;
        String[] candidateMethods = {
            "isGlowing", "hasGlow", "isPlayerGlowing", "isGlowed", "isGlowingPlayer", "hasPlayer", "isTracking"
        };

        for (String mName : candidateMethods) {
            try {
                Method m = glowManager.getClass().getMethod(mName, Player.class);
                Object res = m.invoke(glowManager, (Player)player);
                if (res instanceof Boolean b) return b;
            } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException | java.lang.reflect.InvocationTargetException ignored) {}

            try {
                Method m2 = glowManager.getClass().getMethod(mName, UUID.class);
                Object res2 = m2.invoke(glowManager, (UUID)uuid);
                if (res2 instanceof Boolean b2) return b2;
            } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException | java.lang.reflect.InvocationTargetException ignored) {}
        }

        try {
            Method m = glowManager.getClass().getMethod("isGlowing");
            Object res = m.invoke(glowManager);
            if (res instanceof Boolean b) return b;
        } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException | java.lang.reflect.InvocationTargetException ignored) {}

        return false;
    }

    @Override
    public String getMasterCombatStateWithGlow(UUID uuid) {
        String state = getMasterCombatState(uuid);
        try {
            if (isPlayerGlowing(uuid)) {
                return state + " (Glowing)";
            }
        } catch (Throwable ignored) {}
        return state;
    }

    @Override
    public int getRemainingCombatTime(UUID uuid) {
        if (uuid == null) return 0;
        Combat.CombatRecord record = plugin.getCombatRecords().get(uuid);
        if (record == null) return 0;
        long remainingTime = (record.expiry - System.currentTimeMillis()) / 1000L;
        return Math.max(0, (int)remainingTime);
    }

    @Override
    public long getTotalCombatTime(UUID uuid) {
        if (uuid == null) return 0;
        Combat.CombatRecord record = plugin.getCombatRecords().get(uuid);
        if (record == null) return 0;
        return (System.currentTimeMillis() - record.expiry + 
                plugin.getConfig().getLong("General.duration", 0) * 1000L) / 1000L;
    }

    @Override
    public UUID getCombatOpponent(UUID uuid) {
        if (uuid == null) return null;
        Combat.CombatRecord record = plugin.getCombatRecords().get(uuid);
        return record != null ? record.opponent : null;
    }

    @Override
    public boolean isCombatSystemEnabled() {
        return plugin.isCombatEnabled();
    }

    @Override
    public int getActiveCombatCount() {
        return (int)plugin.getCombatRecords().values().stream()
            .filter(record -> record.expiry > System.currentTimeMillis())
            .count();
    }

    @Override
    public void setCombatVisibility(UUID uuid, boolean visible) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            plugin.setCombatVisibility(player, visible);
        }
    }

    @Override
    public boolean isCombatVisible(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && plugin.isCombatVisible(player);
    }
}