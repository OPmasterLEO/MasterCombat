package net.opmasterleo.combat.manager;

import java.util.Set;
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
    private final Set<UUID> glowingPlayers = ConcurrentHashMap.newKeySet();
    private Combat plugin;

    public void initialize(Combat plugin) {
        this.plugin = plugin;
    }

    public void setGlowing(Player player, boolean glowing) {
        if (player == null) return;
        
        SchedulerUtil.runEntityTask(plugin, player, () -> {
            if (glowing) {
                glowingPlayers.add(player.getUniqueId());
                applyGlowEffect(player);
            } else {
                glowingPlayers.remove(player.getUniqueId());
                removeGlowEffect(player);
            }
        });
    }

    public void trackPlayer(Player player) {
        if (player == null) return;
        
        SchedulerUtil.runEntityTask(plugin, player, () -> {
            if (glowingPlayers.contains(player.getUniqueId())) {
                applyGlowEffect(player);
            }
        });
    }

    public void untrackPlayer(Player player) {
        if (player == null) return;
        glowingPlayers.remove(player.getUniqueId());
    }

    public void cleanup() {
        Player[] players = Bukkit.getOnlinePlayers().toArray(Player[]::new);
        
        SchedulerUtil.batchProcessPlayers(
            plugin,
            plugin.getCombatWorkerPool(),
            players,
            player -> glowingPlayers.contains(player.getUniqueId()),
            result -> {
                if (result.getResult()) {
                    removeGlowEffect(result.getPlayer());
                }
            }
        );
        
        glowingPlayers.clear();
    }
    
    private void applyGlowEffect(Player player) {
        if (player == null) return;
        
        try {
            setGlowingWithPacketEvents(player, true);
            
            if (plugin.getConfig().getBoolean("General.ColoredGlowing", false)) {
                final String teamName = "combat_" + player.getName().substring(0, Math.min(player.getName().length(), 10));
                final String cfgColor = plugin.getConfig().getString("General.GlowingColor", "RED");
                final String ampColor = colorCodeFromName(cfgColor);
                
                SchedulerUtil.runTask(plugin, () -> {
                    Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                    Team team = board.getTeam(teamName);
                    if (team == null) {
                        team = board.registerNewTeam(teamName);
                    }
                    
                    try {
                        Component comp = ChatUtil.parse(ampColor);
                        team.prefix(comp);
                    } catch (Exception e) {
                        plugin.getLogger().warning(String.format("Failed to apply glowing effect to %s: %s", player.getName(), e.getMessage()));
                    }
                    
                    if (!team.hasEntry(player.getName())) {
                        team.addEntry(player.getName());
                    }
                });
            }
        } catch (Exception e) {
            plugin.getLogger().warning(String.format("Failed to apply glowing effect to %s: %s", player.getName(), e.getMessage()));
        }
    }

    private void removeGlowEffect(Player player) {
        if (player == null) return;
        
        try {
            setGlowingWithPacketEvents(player, false);
            
            if (plugin.getConfig().getBoolean("General.ColoredGlowing", false)) {
                final String teamName = "combat_" + player.getName().substring(0, Math.min(player.getName().length(), 10));
                SchedulerUtil.runTask(plugin, () -> {
                    Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
                    Team team = board.getTeam(teamName);
                    if (team != null && team.hasEntry(player.getName())) {
                        team.removeEntry(player.getName());
                        if (team.getEntries().isEmpty()) {
                            try {
                                team.unregister();
                            } catch (Exception ignored) {}
                        }
                    }
                });
            }
        } catch (Exception e) {
            plugin.getLogger().warning(String.format("Failed to remove glowing effect from %s: %s", player.getName(), e.getMessage()));
        }
    }
    
    private void setGlowingWithPacketEvents(Player player, boolean glowing) {
        try {
            int entityId = player.getEntityId();
            byte entityMask = 0;
            if (glowing) {
                entityMask |= 0x40;
            }
            EntityData<Byte> entityData = new EntityData<>(0, EntityDataTypes.BYTE, entityMask);
            WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(
                entityId, 
                java.util.Collections.singletonList(entityData)
            );

            for (Player target : Bukkit.getOnlinePlayers()) {
                try {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(target, metadataPacket);
                } catch (Exception ignored) {
                }
            }
            
        } catch (Exception e) {
            plugin.getLogger().warning(() -> String.format("Failed to set glowing via PacketEvents for %s: %s", player.getName(), e.getMessage()));
            try {
                player.setGlowing(glowing);
            } catch (Exception ex) {
                plugin.getLogger().warning(() -> String.format("Fallback Bukkit glowing also failed for %s: %s", player.getName(), ex.getMessage()));
            }
        }
    }

    public boolean isGlowing(Player player) {
        return player != null && glowingPlayers.contains(player.getUniqueId());
    }

    private static String colorCodeFromName(String name) {
        if (name == null) return "&c";
        return switch (name.trim().toUpperCase()) {
            case "BLACK" -> "&0";
            case "DARK_BLUE", "DARKBLUE", "BLUE" -> "&1";
            case "DARK_GREEN", "DARKGREEN", "GREEN" -> "&2";
            case "DARK_AQUA", "DARKAQUA", "AQUA" -> "&3";
            case "DARK_RED", "DARKRED", "RED" -> "&4";
            case "DARK_PURPLE", "DARKPURPLE", "PURPLE" -> "&5";
            case "GOLD" -> "&6";
            case "GRAY" -> "&7";
            case "DARK_GRAY", "DARKGRAY" -> "&8";
            case "LIGHT_PURPLE", "LIGHTPURPLE", "PINK" -> "&d";
            case "YELLOW" -> "&e";
            case "WHITE" -> "&f";
            case "MAGIC" -> "&k";
            default -> "&c";
        };
    }
}