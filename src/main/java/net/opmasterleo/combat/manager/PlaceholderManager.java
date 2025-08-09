package net.opmasterleo.combat.manager;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.opmasterleo.combat.Combat;

public class PlaceholderManager {

    private static final Map<String, String> customPlaceholders = new ConcurrentHashMap<>();

    public static String formatTime(long seconds) {
        long minutes = TimeUnit.SECONDS.toMinutes(seconds);
        long remainingSeconds = seconds % 60;
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }

    public static String applyPlaceholders(Player player, String message, long time) {
        if (message == null || message.isEmpty()) return "";

        String formattedTime = formatTime(time);
        message = message.replace("%mastercombat_time%", formattedTime);
        message = message.replace("%time%", formattedTime);

        String disableCommand = getDisableCommand();
        message = message.replace("%command%", disableCommand);
        
        Combat combat = Combat.getInstance();
        if (combat != null) {
            message = message.replace("%prefix%", combat.getConfig().getString("Messages.Prefix", ""));
            message = message.replace("%combat_duration%", String.valueOf(combat.getConfig().getLong("General.duration", 0)));
            message = message.replace("%combat_enabled%", String.valueOf(combat.isCombatEnabled()));
        }

        if (player != null) {
            message = message.replace("%player_name%", player.getName());
            message = message.replace("%player_displayname%", player.displayName().toString());
            message = message.replace("%player_uuid%", player.getUniqueId().toString());

            if (isPlaceholderAPILoaded()) {
                message = PlaceholderAPI.setPlaceholders(player, message);
            }
        }

        for (Map.Entry<String, String> entry : customPlaceholders.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }

        return message;
    }

    public static void registerCustomPlaceholder(String placeholder, String value) {
        if (placeholder == null || value == null) return;
        customPlaceholders.put(placeholder, value);
    }

    public static void unregisterCustomPlaceholder(String placeholder) {
        if (placeholder == null) return;
        customPlaceholders.remove(placeholder);
    }

    private static boolean isPlaceholderAPILoaded() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    private static String getDisableCommand() {
        Combat combat = Combat.getInstance();
        if (combat == null) return "removeprotect";

        return combat.getConfig().getString("NewbieProtection.settings.disableCommand", "removeprotect");
    }
}