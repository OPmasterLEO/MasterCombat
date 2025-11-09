package net.opmasterleo.combat.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;

import net.kyori.adventure.text.Component;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;
import net.opmasterleo.combat.util.SchedulerUtil;

public class GlowManager {
    private final Map<UUID, GlowState> glowingPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPacketSent = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> entityIdCache = new ConcurrentHashMap<>();
    private final Map<String, Team> teamCache = new ConcurrentHashMap<>();
    private Combat plugin;
    private static final long PACKET_THROTTLE_MS = 25;
    private static final int CLEANUP_THRESHOLD = 100;
    private volatile boolean enabled = true;
    private volatile boolean glowingConfigEnabled = true;
    private volatile com.github.retrooper.packetevents.manager.player.PlayerManager packetPlayerManager;
    
    private static class GlowState {
        final boolean isGlowing;
        final UUID opponentId;
        
        GlowState(boolean isGlowing, UUID opponentId) {
            this.isGlowing = isGlowing;
            this.opponentId = opponentId;
        }
    }

    public void initialize(Combat plugin) {
        this.plugin = plugin;
        this.glowingConfigEnabled = plugin.getConfig().getBoolean("General.CombatTagGlowing", false);
        try {
            this.packetPlayerManager = PacketEvents.getAPI().getPlayerManager();
        } catch (Exception e) {
            plugin.debug("Failed to cache PacketEvents PlayerManager: " + e.getMessage());
        }
        schedulePeriodicCleanup();
        schedulePeriodicSync();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public void reloadConfig() {
        this.glowingConfigEnabled = plugin.getConfig().getBoolean("General.CombatTagGlowing", false);
    }
    
    private void schedulePeriodicCleanup() {
        SchedulerUtil.runTaskTimerAsync(plugin, () -> {
            long now = System.currentTimeMillis();
            if (lastPacketSent.size() > CLEANUP_THRESHOLD) {
                java.util.Iterator<Map.Entry<UUID, Long>> iter = lastPacketSent.entrySet().iterator();
                while (iter.hasNext()) {
                    if (now - iter.next().getValue() > 60000) {
                        iter.remove();
                    }
                }
            }
            
                if (glowingPlayers.size() > CLEANUP_THRESHOLD) {
                java.util.Iterator<Map.Entry<UUID, GlowState>> iter = glowingPlayers.entrySet().iterator();
                while (iter.hasNext()) {
                    UUID uuid = iter.next().getKey();
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) {
                        iter.remove();
                        entityIdCache.remove(uuid);
                    }
                }
            }            if (teamCache.size() > CLEANUP_THRESHOLD) {
                java.util.Iterator<Map.Entry<String, Team>> iter = teamCache.entrySet().iterator();
                while (iter.hasNext()) {
                    Team team = iter.next().getValue();
                    if (team == null || team.getEntries().isEmpty()) {
                        iter.remove();
                    }
                }
            }
        }, 200L, 200L);
    }

    private void schedulePeriodicSync() {
        SchedulerUtil.runTaskTimerAsync(plugin, () -> {
            try {
                if (!enabled || !glowingConfigEnabled) {
                    return;
                }
                final Map<UUID, Combat.CombatRecord> combatRecordsLocal = plugin.getCombatRecords();
                List<Player> playersToSync = new ArrayList<>();
                for (Map.Entry<UUID, Combat.CombatRecord> entry : combatRecordsLocal.entrySet()) {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p != null && p.isOnline()) {
                        playersToSync.add(p);
                    }
                }

                if (!playersToSync.isEmpty()) {
                    SchedulerUtil.runTask(plugin, () -> {
                        for (Player p : playersToSync) {
                            syncWithCombat(p);
                        }
                    });
                }

                long now = System.currentTimeMillis();
                List<Player> playersNeedingGlow = new ArrayList<>();
                List<UUID> opponentIds = new ArrayList<>();
                final Map<UUID, GlowState> glowingPlayersLocal = glowingPlayers;
                final Map<UUID, Long> lastPacketLocal = lastPacketSent;
                for (Map.Entry<UUID, GlowState> entry : glowingPlayersLocal.entrySet()) {
                    UUID uuid = entry.getKey();
                    GlowState state = entry.getValue();
                    if (state == null || !state.isGlowing) continue;
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline()) continue;

                    Long last = lastPacketLocal.get(uuid);
                    if (last == null || now - last >= 300L) {
                        playersNeedingGlow.add(p);
                        opponentIds.add(state.opponentId);
                    }
                }

                if (!playersNeedingGlow.isEmpty()) {
                    final List<Player> finalPlayers = new ArrayList<>(playersNeedingGlow);
                    final List<UUID> finalOpponents = new ArrayList<>(opponentIds);
                    SchedulerUtil.runTask(plugin, () -> {
                        for (int i = 0; i < finalPlayers.size(); i++) {
                            applyGlowEffect(finalPlayers.get(i), finalOpponents.get(i));
                        }
                    });
                }
            } catch (Exception e) {
                plugin.debug("Glow sync tick error: " + e.getMessage());
            }
        }, 20L, 20L);
    }

    public void setGlowing(Player player, boolean glowing) {
        setGlowing(player, glowing, null);
    }
    
    public void setGlowing(Player player, boolean glowing, UUID opponentId) {
        if (player == null) return;
        
        UUID playerId = player.getUniqueId();
        GlowState currentState = glowingPlayers.get(playerId);
        
        if (currentState != null && currentState.isGlowing == glowing) {
            return;
        }

        if (glowing && (!enabled || !glowingConfigEnabled)) {
            glowingPlayers.remove(playerId);
            removeGlowEffect(player);
            return;
        }
        
        glowingPlayers.put(playerId, new GlowState(glowing, opponentId));

        if (glowing) {
            applyGlowEffect(player, opponentId);
        } else {
            removeGlowEffect(player);
        }
    }
    
    public boolean syncWithCombat(Player player) {
        if (player == null) return false;
        if (!enabled || !glowingConfigEnabled) {
            GlowState current = glowingPlayers.get(player.getUniqueId());
            if (current != null && current.isGlowing) {
                setGlowing(player, false, null);
                return true;
            }
            return false;
        }
        
        UUID playerId = player.getUniqueId();
        Combat.CombatRecord record = plugin.getCombatRecords().get(playerId);
        boolean shouldGlow = record != null && record.expiry > System.currentTimeMillis();
        
        GlowState currentState = glowingPlayers.get(playerId);
        boolean isCurrentlyGlowing = currentState != null && currentState.isGlowing;
        
        if (shouldGlow != isCurrentlyGlowing) {
            setGlowing(player, shouldGlow, record != null ? record.opponent : null);
            return true;
        }
        
        return false;
    }

    public void trackPlayer(Player player) {
        if (player == null) return;
        
        UUID playerId = player.getUniqueId();
        GlowState state = glowingPlayers.get(playerId);
        
        if (state != null && state.isGlowing) {
            applyGlowEffect(player, state.opponentId);
        }
    }

    public void untrackPlayer(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        glowingPlayers.remove(uuid);
        lastPacketSent.remove(uuid);
        entityIdCache.remove(uuid);
    }

    public void cleanup() {
        if (glowingPlayers.isEmpty()) return;
        for (Map.Entry<UUID, GlowState> entry : glowingPlayers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                GlowState state = entry.getValue();
                if (state != null && state.isGlowing) {
                    removeGlowEffect(player);
                }
            }
        }
        
        glowingPlayers.clear();
        lastPacketSent.clear();
        entityIdCache.clear();
        
        SchedulerUtil.runTask(plugin, () -> {
            for (Team team : teamCache.values()) {
                if (team != null) {
                    try {
                        team.unregister();
                    } catch (Exception ignored) {}
                }
            }
            teamCache.clear();
        });
    }
    
    private void applyGlowEffect(Player player, UUID opponentId) {
        if (player == null) return;
        if (!enabled || !glowingConfigEnabled) return;
        
        try {
            setGlowingWithPacketEvents(player, true, opponentId);
            Combat combatPlugin = (Combat) plugin;
            if (combatPlugin.getCachedColoredGlowing()) {
                final String teamName = "combat_" + player.getName().substring(0, Math.min(player.getName().length(), 10));
                
                SchedulerUtil.runTask(plugin, () -> {
                    try {
                        Team team = teamCache.computeIfAbsent(teamName, name -> {
                            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                            Team existingTeam = board.getTeam(name);
                            if (existingTeam != null) {
                                return existingTeam;
                            }
                            try {
                                return board.registerNewTeam(name);
                            } catch (Exception e) {
                                plugin.debug("Failed to register team: " + name);
                                return null;
                            }
                        });
                        
                        if (team != null) {
                            Component playerColor = extractPlayerColor(player);
                            team.prefix(playerColor);
                            
                            if (!team.hasEntry(player.getName())) {
                                team.addEntry(player.getName());
                            }
                        }
                    } catch (Exception e) {
                        plugin.debug("Error applying colored glowing: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            plugin.debug("Failed to apply glowing effect to " + player.getName() + ": " + e.getMessage());
        }
    }

    private void removeGlowEffect(Player player) {
        if (player == null) return;
        
        try {
            setGlowingWithPacketEvents(player, false, null);
            Combat combatPlugin = (Combat) plugin;
            if (combatPlugin.getCachedColoredGlowing()) {
                final String teamName = "combat_" + player.getName().substring(0, Math.min(player.getName().length(), 10));
                SchedulerUtil.runTask(plugin, () -> {
                    Team team = teamCache.remove(teamName);
                    if (team == null) {
                        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                        team = board.getTeam(teamName);
                    }
                    
                    if (team != null) {
                        if (team.hasEntry(player.getName())) {
                            team.removeEntry(player.getName());
                        }
                        if (team.getEntries().isEmpty()) {
                            try {
                                team.unregister();
                            } catch (Exception ignored) {}
                        }
                    }
                });
            }
        } catch (Exception e) {
            plugin.debug("Failed to remove glowing effect from " + player.getName() + ": " + e.getMessage());
        }
    }
    
    private void setGlowingWithPacketEvents(Player player, boolean glowing, UUID opponentId) {
        if (player == null) return;
        if (!enabled || !glowingConfigEnabled) {
            if (glowing) return;
        }
        
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastSent = lastPacketSent.get(playerId);
        
        if (lastSent != null && now - lastSent < PACKET_THROTTLE_MS) {
            return;
        }
        
        try {
            int entityId = entityIdCache.computeIfAbsent(playerId, k -> player.getEntityId());
            List<EntityData<?>> metadata = new ArrayList<>(2);
            byte flags = 0x00;
            if (glowing) flags |= 0x40;
            if (player.isSneaking()) flags |= 0x02;
            if (player.isSprinting()) flags |= 0x08;
            if (player.isInvisible()) flags |= 0x20;
            metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, flags));
            
            WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(
                entityId, 
                metadata
            );

            if (opponentId != null && packetPlayerManager != null) {
                Player opponent = Bukkit.getPlayer(opponentId);
                if (opponent != null && opponent.isOnline() && !opponent.equals(player)) {
                    try {
                        packetPlayerManager.sendPacket(opponent, metadataPacket);
                    } catch (Exception ignored) {}
                }
            }
            
            lastPacketSent.put(playerId, now);
            
        } catch (Exception e) {
            plugin.debug("Failed to set glowing via PacketEvents for " + player.getName() + ": " + e.getMessage());
            try {
                player.setGlowing(glowing);
            } catch (Exception ex) {
                plugin.debug("Fallback Bukkit glowing also failed for " + player.getName() + ": " + ex.getMessage());
            }
        }
    }

    public boolean isGlowing(Player player) {
        if (player == null) return false;
        GlowState state = glowingPlayers.get(player.getUniqueId());
        return state != null && state.isGlowing;
    }

    private Component extractPlayerColor(Player player) {
        Component displayName = player.displayName();
        net.kyori.adventure.text.format.TextColor color = ChatUtil.getLastColor(displayName);
        
        if (color != null) {
            return Component.empty().color(color);
        }

        return Component.empty().color(net.kyori.adventure.text.format.NamedTextColor.RED);
    }
}