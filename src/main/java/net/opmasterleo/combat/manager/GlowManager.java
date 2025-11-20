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

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import net.kyori.adventure.text.Component;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;
import net.opmasterleo.combat.util.SchedulerUtil;

public class GlowManager {
    private final ConcurrentLong2ReferenceChainedHashTable<GlowState> glowingPlayers = ConcurrentLong2ReferenceChainedHashTable.createWithExpected(256, 0.75f);
    private final ConcurrentLong2ReferenceChainedHashTable<Long> lastPacketSent = ConcurrentLong2ReferenceChainedHashTable.createWithExpected(256, 0.75f);
    private final ConcurrentLong2ReferenceChainedHashTable<Integer> entityIdCache = ConcurrentLong2ReferenceChainedHashTable.createWithExpected(256, 0.75f);
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
                for (Player p : Bukkit.getOnlinePlayers()) {
                    long key = Combat.uuidToLong(p.getUniqueId());
                    Long ts = lastPacketSent.get(key);
                    if (ts != null && now - ts > 60000) {
                        lastPacketSent.remove(key);
                    }
                }
            }

            if (glowingPlayers.size() > CLEANUP_THRESHOLD) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    long key = Combat.uuidToLong(p.getUniqueId());
                    GlowState state = glowingPlayers.get(key);
                    if (state == null) {
                        continue;
                    }
                    if (p == null || !p.isOnline()) {
                        glowingPlayers.remove(key);
                        entityIdCache.remove(key);
                    }
                }
            }
            if (teamCache.size() > CLEANUP_THRESHOLD) {
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
                List<Player> playersToSync = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p != null && p.isOnline() && plugin.getCombatRecords().get(Combat.uuidToLong(p.getUniqueId())) != null) {
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
                List<Player> playersNeedingGlow = new ArrayList<>(16);
                List<UUID> opponentIds = new ArrayList<>(16);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    long key = Combat.uuidToLong(online.getUniqueId());
                    GlowState state = glowingPlayers.get(key);
                    if (state == null || !state.isGlowing) continue;
                    Long last = lastPacketSent.get(key);
                    if (last == null || now - last >= 300L) {
                        playersNeedingGlow.add(online);
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
        
        long playerKey = Combat.uuidToLong(player.getUniqueId());
        GlowState currentState = glowingPlayers.get(playerKey);
        
        if (currentState != null && currentState.isGlowing == glowing) {
            return;
        }

        if (glowing && (!enabled || !glowingConfigEnabled)) {
            glowingPlayers.remove(playerKey);
            removeGlowEffect(player);
            return;
        }
        glowingPlayers.put(playerKey, new GlowState(glowing, opponentId));

        if (glowing) {
            applyGlowEffect(player, opponentId);
        } else {
            removeGlowEffect(player);
        }
    }
    
    public boolean syncWithCombat(Player player) {
        if (player == null) return false;
        long key = Combat.uuidToLong(player.getUniqueId());
        if (!enabled || !glowingConfigEnabled) {
            GlowState current = glowingPlayers.get(key);
            if (current != null && current.isGlowing) {
                setGlowing(player, false, null);
                return true;
            }
            return false;
        }
        Combat.CombatRecord record = plugin.getCombatRecords().get(key);
        boolean shouldGlow = record != null && record.expiry > System.currentTimeMillis();
        
        GlowState currentState = glowingPlayers.get(key);
        boolean isCurrentlyGlowing = currentState != null && currentState.isGlowing;
        
        if (shouldGlow != isCurrentlyGlowing) {
            setGlowing(player, shouldGlow, record != null ? record.opponent : null);
            return true;
        }
        
        return false;
    }

    public void trackPlayer(Player player) {
        if (player == null) return;
        
        long playerKey = Combat.uuidToLong(player.getUniqueId());
        GlowState state = glowingPlayers.get(playerKey);
        
        if (state != null && state.isGlowing) {
            applyGlowEffect(player, state.opponentId);
        }
    }

    public void untrackPlayer(Player player) {
        if (player == null) return;
        long key = Combat.uuidToLong(player.getUniqueId());
        glowingPlayers.remove(key);
        lastPacketSent.remove(key);
        entityIdCache.remove(key);
    }

    public void cleanup() {
        if (glowingPlayers.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            long k = Combat.uuidToLong(p.getUniqueId());
            GlowState state = glowingPlayers.get(k);
            if (state != null && state.isGlowing) {
                removeGlowEffect(p);
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
        
        long playerKey = Combat.uuidToLong(player.getUniqueId());
        long now = System.currentTimeMillis();
        Long lastSent = lastPacketSent.get(playerKey);
        
        if (lastSent != null && now - lastSent < PACKET_THROTTLE_MS) {
            return;
        }
        
        try {
            Integer cachedId = entityIdCache.get(playerKey);
            int entityId;
            if (cachedId == null) {
                entityId = player.getEntityId();
                entityIdCache.put(playerKey, entityId);
            } else {
                entityId = cachedId;
            }
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
            
            lastPacketSent.put(playerKey, now);
            
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
        GlowState state = glowingPlayers.get(Combat.uuidToLong(player.getUniqueId()));
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
