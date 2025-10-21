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
        String disableCommand = getDisableCommand();
        message = message.replace("%mastercombat_command%", disableCommand);
        message = message.replace("%command%", disableCommand);
        
        Combat combat = Combat.getInstance();
        if (combat != null) {
            String prefix = combat.getConfig().getString("Messages.Prefix", "");
            long duration = combat.getConfig().getLong("General.duration", 0);
            boolean enabled = combat.isCombatEnabled();

            // Prefixed placeholders
            message = message.replace("%mastercombat_prefix%", prefix);
            message = message.replace("%mastercombat_duration%", String.valueOf(duration));
            message = message.replace("%mastercombat_enabled%", String.valueOf(enabled));
            message = message.replace("%mastercombat_status%", enabled ? "Fighting" : "Idle");

            // Legacy fallbacks (kept for backward compatibility)
            message = message.replace("%prefix%", prefix);
            message = message.replace("%combat_duration%", String.valueOf(duration));
            message = message.replace("%combat_enabled%", String.valueOf(enabled));
        }

        if (player != null) {
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