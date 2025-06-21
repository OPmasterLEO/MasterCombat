package net.opmasterleo.combat.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.api.MasterCombatAPIProvider;
import net.opmasterleo.combat.listener.NewbieProtectionListener;
import net.opmasterleo.combat.manager.PlaceholderManager;
import net.opmasterleo.combat.manager.Update;

public class CombatCommand implements CommandExecutor, TabCompleter {

    private static boolean updateCheckInProgress = false;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.").color(NamedTextColor.RED));
            return true;
        }

        Combat combat = Combat.getInstance();
        NewbieProtectionListener protectionListener = combat.getNewbieProtectionListener();

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "removeprotect":
                if (!protectionListener.isProtected(player)) {
                    player.sendMessage(Component.text("You are not protected.").color(NamedTextColor.RED));
                    return true;
                }
                String disableMessage = PlaceholderManager.applyPlaceholders(player,
                        combat.getConfig().getString("NewbieProtection.disableMessage"), 0);
                player.sendMessage(Component.text(disableMessage));
                break;

            case "confirm":
                protectionListener.removeProtection(player);
                player.sendMessage(Component.text("PvP protection disabled. You are now vulnerable.").color(NamedTextColor.YELLOW));
                break;

            case "reload":
                Combat.getInstance().reloadCombatConfig();
                sender.sendMessage(Component.text("Config reloaded!").color(NamedTextColor.GREEN));
                break;

            case "toggle":
                Combat combatInstance = Combat.getInstance();
                combatInstance.setCombatEnabled(!combatInstance.isCombatEnabled());
                String status = combatInstance.isCombatEnabled() ? "enabled" : "disabled";
                NamedTextColor statusColor = combatInstance.isCombatEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED;
                sender.sendMessage(Component.text("Combat has been ").color(NamedTextColor.YELLOW)
                        .append(Component.text(status).color(statusColor))
                        .append(Component.text(".").color(NamedTextColor.YELLOW)));
                break;

            case "update":
                if (updateCheckInProgress) {
                    sender.sendMessage(Component.text("Update check is already in progress. Please wait...").color(NamedTextColor.YELLOW));
                    break;
                }
                updateCheckInProgress = true;
                sender.sendMessage(Component.text("Checking for updates...").color(NamedTextColor.YELLOW));
                Combat plugin = Combat.getInstance();
                Update.checkForUpdates(plugin);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    @SuppressWarnings("deprecation")
                    String currentVersion = plugin.getDescription().getVersion();
                    String latestVersion = Update.getLatestVersion();
                    updateCheckInProgress = false;
                    if (latestVersion == null) {
                        sender.sendMessage(Component.text("Could not fetch update information.").color(NamedTextColor.RED));
                        return;
                    }
                    if (normalizeVersion(currentVersion).equalsIgnoreCase(normalizeVersion(latestVersion))) {
                        sender.sendMessage(Component.text("You already have the latest version (" + currentVersion + ").").color(NamedTextColor.GREEN));
                        return;
                    }
                    sender.sendMessage(Component.text("Downloading and applying the update (if available)...").color(NamedTextColor.YELLOW));
                    Update.downloadAndReplaceJar(plugin);
                }, 40L);
                break;

            case "api":
                if (MasterCombatAPIProvider.getAPI() != null) {
                    sender.sendMessage(Component.text("MasterCombatAPI is loaded and available.").color(NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("MasterCombatAPI is not available.").color(NamedTextColor.RED));
                }
                break;

            default:
                sendHelp(sender);
        }
        return true;
    }

    private String normalizeVersion(String version) {
        return version.replaceAll("[^0-9.]", "");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("Usage:").color(NamedTextColor.RED));
        sender.sendMessage(Component.text("/combat reload").color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/combat toggle").color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/combat update").color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/combat api").color(NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if ("reload".startsWith(args[0].toLowerCase())) {
                completions.add("reload");
            }
            if ("toggle".startsWith(args[0].toLowerCase())) {
                completions.add("toggle");
            }
            if ("update".startsWith(args[0].toLowerCase())) {
                completions.add("update");
            }
            if ("api".startsWith(args[0].toLowerCase())) {
                completions.add("api");
            }
        }

        return completions;
    }
}