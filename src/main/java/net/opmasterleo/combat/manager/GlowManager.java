package net.opmasterleo.combat.manager;

import java.util.ArrayList;
import java.util.Collection;
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
    private final Map<String, Team> teamCache = new ConcurrentHashMap<>();
    private Combat plugin;
    private static final long PACKET_THROTTLE_MS = 50;
    private static final int CLEANUP_THRESHOLD = 100;
    
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
        schedulePeriodicCleanup();
    }
    
    private void schedulePeriodicCleanup() {
        SchedulerUtil.runTaskTimerAsync(plugin, () -> {
            long now = System.currentTimeMillis();
            
            if (lastPacketSent.size() > CLEANUP_THRESHOLD) {
                lastPacketSent.entrySet().removeIf(entry -> now - entry.getValue() > 60000);
            }
            
            if (glowingPlayers.size() > CLEANUP_THRESHOLD) {
                List<UUID> toRemove = new ArrayList<>();
                for (Map.Entry<UUID, GlowState> entry : glowingPlayers.entrySet()) {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player == null || !player.isOnline()) {
                        toRemove.add(entry.getKey());
                    }
                }
                toRemove.forEach(glowingPlayers::remove);
            }
            
            if (teamCache.size() > CLEANUP_THRESHOLD) {
                teamCache.entrySet().removeIf(entry -> {
                    Team team = entry.getValue();
                    return team == null || team.getEntries().isEmpty();
                });
            }
        }, 200L, 200L);
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
        
        glowingPlayers.put(playerId, new GlowState(glowing, opponentId));
        
        SchedulerUtil.runEntityTask(plugin, player, () -> {
            if (glowing) {
                applyGlowEffect(player, opponentId);
            } else {
                removeGlowEffect(player);
            }
        });
    }
    
    public boolean syncWithCombat(Player player) {
        if (player == null) return false;
        
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
            SchedulerUtil.runEntityTask(plugin, player, () -> {
                applyGlowEffect(player, state.opponentId);
            });
        }
    }

    public void untrackPlayer(Player player) {
        if (player == null) return;
        glowingPlayers.remove(player.getUniqueId());
        lastPacketSent.remove(player.getUniqueId());
    }

    public void cleanup() {
        if (glowingPlayers.isEmpty()) return;
        
        Collection<UUID> playerIds = new ArrayList<>(glowingPlayers.keySet());
        
        for (UUID playerId : playerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                GlowState state = glowingPlayers.get(playerId);
                if (state != null && state.isGlowing) {
                    removeGlowEffect(player);
                }
            }
        }
        
        glowingPlayers.clear();
        lastPacketSent.clear();
        
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
        
        try {
            setGlowingWithPacketEvents(player, true, opponentId);
            
            if (plugin.getConfig().getBoolean("General.ColoredGlowing", false)) {
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
            
            if (plugin.getConfig().getBoolean("General.ColoredGlowing", false)) {
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
        
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastSent = lastPacketSent.get(playerId);
        
        if (lastSent != null && now - lastSent < PACKET_THROTTLE_MS) {
            return;
        }
        
        try {
            int entityId = player.getEntityId();
            byte flags = 0x00;
            if (glowing) {
                flags |= 0x40;
            }
            if (player.isSneaking()) {
                flags |= 0x02;
            }
            if (player.isSprinting()) {
                flags |= 0x08;
            }
            if (player.isInvisible()) {
                flags |= 0x20;
            }
            
            EntityData<Byte> entityData = new EntityData<>(0, EntityDataTypes.BYTE, flags);
            List<EntityData<?>> metadata = new ArrayList<>();
            metadata.add(entityData);
            WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(
                entityId, 
                metadata
            );

            List<Player> recipients = new ArrayList<>();
            
            if (opponentId != null) {
                Player opponent = Bukkit.getPlayer(opponentId);
                if (opponent != null && opponent.isOnline() && !opponent.equals(player)) {
                    recipients.add(opponent);
                }
            }
            
            if (recipients.isEmpty()) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (!online.equals(player) && online.canSee(player)) {
                        recipients.add(online);
                    }
                }
            }
            
            for (Player recipient : recipients) {
                try {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(recipient, metadataPacket);
                } catch (Exception ignored) {}
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