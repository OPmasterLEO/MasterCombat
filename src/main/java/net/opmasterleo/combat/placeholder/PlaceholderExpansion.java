package net.opmasterleo.combat.placeholder;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.api.MasterCombatAPI;
import net.opmasterleo.combat.api.MasterCombatAPIProvider;

public class PlaceholderExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {

    private final Combat plugin;

    public PlaceholderExpansion(Combat plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "mastercombat";
    }

    @Override
    public String getAuthor() {
        try {
            List<String> authors = plugin.getPluginMeta().getAuthors();
            return String.join(", ", authors);
        } catch (Throwable ignored) {
            return "opmasterleo";
        }
    }

    @Override
    public String getVersion() {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (params == null) return "";
        String key = params.toLowerCase(Locale.ROOT);
        MasterCombatAPI api = MasterCombatAPIProvider.getAPI();

        return switch (key) {
            case "time" -> {
                if (player == null || api == null) yield "00:00";
                int secs = api.getRemainingCombatTime(player.getUniqueId());
                yield net.opmasterleo.combat.placeholder.PlaceholderAPI.formatTime(secs);
            }
            case "command" ->
                plugin.getConfig().getString("NewbieProtection.settings.disableCommand", "removeprotect");
            case "prefix" ->
                plugin.getPrefix();
            case "duration" ->
                String.valueOf(plugin.getConfig().getLong("General.duration", 0));
            case "enabled" ->
                String.valueOf(plugin.isCombatEnabled());
            case "status" ->
                plugin.isCombatEnabled() ? "ON" : "OFF";
            case "visibility" -> {
                if (player == null) yield "on";
                yield plugin.isCombatVisible(player) ? "on" : "off";
            }
            case "opponent" -> {
                if (player == null || api == null) yield "";
                UUID opp = api.getCombatOpponent(player.getUniqueId());
                if (opp == null) yield "";
                Player oppPlayer = Bukkit.getPlayer(opp);
                yield oppPlayer != null ? oppPlayer.getName() : "";
            }
            default -> "";
        };
    }
}
