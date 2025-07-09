package net.opmasterleo.combat.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GlowManager {

    private final Set<UUID> glowingPlayers = Collections.synchronizedSet(new HashSet<>());
    private final Map<Integer, UUID> entityIdMap = new ConcurrentHashMap<>();
    private final boolean glowingEnabled;
    private final boolean packetEventsAvailable;

    public GlowManager() {
        this.glowingEnabled = isGlowingEnabled();
        this.packetEventsAvailable = isPacketEventsAvailable();
        if (glowingEnabled && packetEventsAvailable) {
            registerListener();
            startTracking();
        }
    }

    private boolean isGlowingEnabled() {
        try {
            return org.bukkit.Bukkit.getPluginManager()
                .getPlugin("MasterCombat") != null &&
                net.opmasterleo.combat.Combat.getInstance()
                    .getConfig().getBoolean("CombatTagGlowing.Enabled", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isPacketEventsAvailable() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void registerListener() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents")
                .getMethod("getAPI").invoke(null);
        } catch (Throwable ignored) {}
    }

    private void unregisterListener() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents")
                .getMethod("getAPI").invoke(null);
        } catch (Throwable ignored) {}
    }

    private void startTracking() {
        if (!glowingEnabled || !packetEventsAvailable) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            trackPlayer(player);
        }
    }

    public void trackPlayer(Player player) {
        if (!glowingEnabled || !packetEventsAvailable) return;
        entityIdMap.put(player.getEntityId(), player.getUniqueId());
    }

    public void untrackPlayer(Player player) {
        if (!glowingEnabled || !packetEventsAvailable) return;
        entityIdMap.remove(player.getEntityId());
    }

    public void setGlowing(Player player, boolean glowing) {
        if (!glowingEnabled || !packetEventsAvailable) return;
        if (glowing) {
            glowingPlayers.add(player.getUniqueId());
        } else {
            glowingPlayers.remove(player.getUniqueId());
        }
        updateGlowing(player);
    }

    private void updateGlowing(Player player) {
    }

    public void cleanup() {
        if (!glowingEnabled || !packetEventsAvailable) return;
        unregisterListener();
        glowingPlayers.clear();
        entityIdMap.clear();
    }
}