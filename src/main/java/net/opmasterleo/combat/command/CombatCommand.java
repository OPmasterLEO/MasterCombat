package net.opmasterleo.combat.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.api.MasterCombatAPIProvider;
import net.opmasterleo.combat.listener.NewbieProtectionListener;
import net.opmasterleo.combat.manager.Update;

public class CombatCommand implements CommandExecutor, TabCompleter {

    private static boolean updateCheckInProgress = false;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();
        
        combat.getCombatPlayers().remove(player.getUniqueId());
        Player opponent = combat.getCombatOpponent(player);
        combat.getCombatOpponents().remove(player.getUniqueId());
        
        if (combat.getGlowManager() != null) {
            combat.getGlowManager().setGlowing(player, false);
            if (opponent != null) {
                combat.getGlowManager().setGlowing(opponent, false);
            }
        }
        
        if (opponent != null) {
            combat.getCombatPlayers().remove(opponent.getUniqueId());
            combat.getCombatOpponents().remove(opponent.getUniqueId());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Combat combat = Combat.getInstance();
        NewbieProtectionListener protectionListener = combat.getNewbieProtectionListener();
        String disableCommand = combat.getConfig().getString("NewbieProtection.settings.disableCommand", "removeprotect").toLowerCase();

        String cmdLabel = label.toLowerCase();

        if (cmdLabel.equals("protection")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can use this command.").color(NamedTextColor.RED));
                return true;
            }
            
            if (protectionListener != null && protectionListener.isActuallyProtected(player)) {
                protectionListener.sendProtectionMessage(player);
            } else {
                player.sendMessage(Component.text("You are not protected.").color(NamedTextColor.RED));
            }
            return true;
        }

        if (cmdLabel.equals(disableCommand)) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can use this command.").color(NamedTextColor.RED));
                return true;
            }
            
            if (protectionListener == null || !protectionListener.isActuallyProtected(player)) {
                player.sendMessage(Component.text("You are not protected.").color(NamedTextColor.RED));
                return true;
            }
            
            if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
                protectionListener.removeProtection(player);
                return true;
            }
            
            player.sendMessage(Component.text("Are you sure you want to remove your protection? ")
                    .color(NamedTextColor.YELLOW)
                    .append(Component.text("Run '/" + disableCommand + " confirm' to confirm.").color(NamedTextColor.RED)));
            return true;
        }

        if (cmdLabel.equals("combat")) {
            if (args.length == 0) {
                sendHelp(sender, disableCommand);
                return true;
            }
            
            switch (args[0].toLowerCase()) {
                case "reload":
                    long startTime = System.nanoTime();
                    combat.reloadCombatConfig();
                    long endTime = System.nanoTime();
                    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                    sender.sendMessage(Component.text("Config reloaded in " + durationMs + "ms!").color(NamedTextColor.GREEN));
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

                case "removeprotect":
                case "protection":
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(Component.text("Only players can use this command.").color(NamedTextColor.RED));
                        return true;
                    }
                    
                    if (args[0].equalsIgnoreCase(disableCommand)) {
                        if (protectionListener == null || !protectionListener.isActuallyProtected(player)) {
                            player.sendMessage(Component.text("You are not protected.").color(NamedTextColor.RED));
                            return true;
                        }
                        player.sendMessage(Component.text("Are you sure you want to remove your protection? ")
                                .color(NamedTextColor.YELLOW)
                                .append(Component.text("Run '/"+ disableCommand +" confirm' to confirm.").color(NamedTextColor.RED)));
                        return true;
                    }
                    break;
                
                case "confirm":
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(Component.text("Only players can use this command.").color(NamedTextColor.RED));
                        return true;
                    }

                    if (protectionListener == null || !protectionListener.isActuallyProtected(player)) {
                        player.sendMessage(Component.text("You don't have active protection.").color(NamedTextColor.RED));
                        return true;
                    }
                    protectionListener.removeProtection(player);
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
                        String currentVersion = plugin.getPluginMeta().getVersion();
                        String latestVersion = Update.getLatestVersion();
                        updateCheckInProgress = false;
                        if (latestVersion == null) {
                            sender.sendMessage(Component.text("Could not fetch update information.").color(NamedTextColor.RED));
                            return;
                        }
                        if (normalizeVersion(currentVersion).equalsIgnoreCase(normalizeVersion(latestVersion))) {
                            sender.sendMessage(Component.text("You already have the latest version (" + currentVersion + ").").color(NamedTextColor.GREEN));
                            Update.setUpdateFound(false);
                            return;
                        }
                        if (!Update.isUpdateFound()) {
                            sender.sendMessage(Component.text("Update found! Run /combat update again to update.").color(NamedTextColor.YELLOW));
                            Update.setUpdateFound(true);
                        } else {
                            sender.sendMessage(Component.text("Downloading the update...").color(NamedTextColor.YELLOW));
                            Update.downloadAndReplaceJar(plugin);
                            Update.setUpdateFound(false);
                        }
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
                    sendHelp(sender, disableCommand);
            }
            return true;
        }
        return false;
    }

    private String normalizeVersion(String version) {
        return version.replaceAll("[^0-9.]", "");
    }

    private void sendHelp(CommandSender sender, String disableCommand) {
        sender.sendMessage(Component.text("Usage:").color(NamedTextColor.RED));
        sender.sendMessage(Component.text("/combat reload").color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/combat toggle").color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/combat update").color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/combat api").color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/combat " + disableCommand).color(NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/combat protection").color(NamedTextColor.GRAY));
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
            if ("protection".startsWith(args[0].toLowerCase())) {
                completions.add("protection");
            }
        }

        return completions;
    }
}