package net.opmasterleo.combat.manager;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;
import net.opmasterleo.combat.util.SchedulerUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
        Player[] players = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        
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
            player.setGlowing(true);
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
                        Method m = Team.class.getMethod("setPrefix", Component.class);
                        m.invoke(team, comp);
                    } catch (NoSuchMethodException nsme) {
                        try {
                            String legacy = ampColor.replace('&', '\u00A7');
                            Method m2 = Team.class.getMethod("setPrefix", String.class);
                            m2.invoke(team, legacy);
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                    if (!team.hasEntry(player.getName())) {
                        team.addEntry(player.getName());
                    }
                });
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to apply glowing effect to " + player.getName() + ": " + e.getMessage());
        }
    }

    private void removeGlowEffect(Player player) {
        if (player == null) return;
        
        try {
            player.setGlowing(false);
            
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
            plugin.getLogger().warning("Failed to remove glowing effect from " + player.getName() + ": " + e.getMessage());
        }
    }

    public boolean isGlowing(Player player) {
        return player != null && glowingPlayers.contains(player.getUniqueId());
    }

    private static String colorCodeFromName(String name) {
        if (name == null) return "&c"; // default red
        switch (name.trim().toUpperCase()) {
            case "BLACK": return "&0";
            case "DARK_BLUE": case "DARKBLUE": case "BLUE": return "&1";
            case "DARK_GREEN": case "DARKGREEN": case "GREEN": return "&2";
            case "DARK_AQUA": case "DARKAQUA": case "AQUA": return "&3";
            case "DARK_RED": case "DARKRED": case "RED": return "&4";
            case "DARK_PURPLE": case "DARKPURPLE": case "PURPLE": return "&5";
            case "GOLD": return "&6";
            case "GRAY": return "&7";
            case "DARK_GRAY": case "DARKGRAY": return "&8";
            case "LIGHT_PURPLE": case "LIGHTPURPLE": case "PINK": return "&d";
            case "YELLOW": return "&e";
            case "WHITE": return "&f";
            case "MAGIC": return "&k";
            default: return "&c";
        }
    }
}