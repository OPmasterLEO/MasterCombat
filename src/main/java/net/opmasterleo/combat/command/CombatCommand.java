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

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.api.MasterCombatAPIProvider;
import net.opmasterleo.combat.listener.NewbieProtectionListener;
import net.opmasterleo.combat.manager.Update;
import net.opmasterleo.combat.util.ChatUtil;

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
        String pluginName = combat.getPluginMeta().getDisplayName();
        String pluginVersion = combat.getPluginMeta().getVersion();
        String pluginDescription = combat.getPluginMeta().getDescription();

        String cmdLabel = label.toLowerCase();

        if (cmdLabel.equals("protection")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                return true;
            }
            
            if (protectionListener != null && protectionListener.isActuallyProtected(player)) {
                protectionListener.sendProtectionMessage(player);
            } else {
                player.sendMessage(ChatUtil.parse("&cYou are not protected."));
            }
            return true;
        }

        if (cmdLabel.equals(disableCommand)) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                return true;
            }
            if (protectionListener == null || !protectionListener.isActuallyProtected(player)) {
                player.sendMessage(ChatUtil.parse("&cYou are not protected."));
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
                protectionListener.removeProtection(player);
                return true;
            }
            player.sendMessage(ChatUtil.parse("&eAre you sure you want to remove your protection? &cRun '/" + disableCommand + " confirm' to confirm."));
            return true;
        }

        if (cmdLabel.equals("combat")) {
            if (args.length == 0) {
                sender.sendMessage(ChatUtil.parse("&b" + pluginName + " &7v" + pluginVersion));
                sender.sendMessage(ChatUtil.parse("&7" + pluginDescription));
                sender.sendMessage(ChatUtil.parse("&7Type &e/combat help &7for command list."));
                return true;
            }
            if (args[0].equalsIgnoreCase("help")) {
                sendHelp(sender, disableCommand);
                return true;
            }
            
            switch (args[0].toLowerCase()) {
                case "reload":
                    long startTime = System.nanoTime();
                    combat.reloadCombatConfig();
                    long endTime = System.nanoTime();
                    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                    sender.sendMessage(ChatUtil.parse("&aConfig reloaded in " + durationMs + "ms!"));
                    break;
                    
                case "toggle":
                    Combat combatInstance = Combat.getInstance();
                    combatInstance.setCombatEnabled(!combatInstance.isCombatEnabled());
                    sender.sendMessage(ChatUtil.parse("&eCombat has been &" + (combatInstance.isCombatEnabled() ? "aenabled" : "cdisabled") + "&e."));
                    break;

                case "removeprotect":
                case "protection":
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                        return true;
                    }
                    
                    if (args[0].equalsIgnoreCase(disableCommand)) {
                        if (protectionListener == null || !protectionListener.isActuallyProtected(player)) {
                            player.sendMessage(ChatUtil.parse("&cYou are not protected."));
                            return true;
                        }
                        player.sendMessage(ChatUtil.parse("&eAre you sure you want to remove your protection? &cRun '/"+ disableCommand +" confirm' to confirm."));
                        return true;
                    }
                    break;
                
                case "confirm":
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                        return true;
                    }

                    if (protectionListener == null || !protectionListener.isActuallyProtected(player)) {
                        player.sendMessage(ChatUtil.parse("&cYou don't have active protection."));
                        return true;
                    }
                    protectionListener.removeProtection(player);
                    break;
                
                case "update":
                    if (updateCheckInProgress) {
                        sender.sendMessage(ChatUtil.parse("&eUpdate check is already in progress. Please wait..."));
                        break;
                    }
                    updateCheckInProgress = true;
                    sender.sendMessage(ChatUtil.parse("&eChecking for updates..."));
                    Combat plugin = Combat.getInstance();
                    Update.checkForUpdates(plugin);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        String currentVersion = plugin.getPluginMeta().getVersion();
                        String latestVersion = Update.getLatestVersion();
                        updateCheckInProgress = false;
                        if (latestVersion == null) {
                            sender.sendMessage(ChatUtil.parse("&cCould not fetch update information."));
                            return;
                        }
                        if (normalizeVersion(currentVersion).equalsIgnoreCase(normalizeVersion(latestVersion))) {
                            sender.sendMessage(ChatUtil.parse("&aYou already have the latest version (" + currentVersion + ")."));
                            Update.setUpdateFound(false);
                            return;
                        }
                        if (!Update.isUpdateFound()) {
                            sender.sendMessage(ChatUtil.parse("&eUpdate found! Run /combat update again to update."));
                            Update.setUpdateFound(true);
                        } else {
                            sender.sendMessage(ChatUtil.parse("&eDownloading the update..."));
                            Update.downloadAndReplaceJar(plugin);
                            Update.setUpdateFound(false);
                        }
                    }, 40L);
                    break;
                case "api":
                    if (MasterCombatAPIProvider.getAPI() != null) {
                        sender.sendMessage(ChatUtil.parse("&aMasterCombatAPI is loaded and available."));
                    } else {
                        sender.sendMessage(ChatUtil.parse("&cMasterCombatAPI is not available."));
                    }
                    break;
                default:
                    sender.sendMessage(ChatUtil.parse("&cUnknown command. Type &e/combat help &cfor usage."));
            }
            return true;
        }
        return false;
    }

    private String normalizeVersion(String version) {
        return version.replaceAll("[^0-9.]", "");
    }

    private void sendHelp(CommandSender sender, String disableCommand) {
        sender.sendMessage(ChatUtil.parse("&eMasterCombat Command List:"));
        sender.sendMessage(ChatUtil.parse("/combat reload &7- Reloads the plugin configuration."));
        sender.sendMessage(ChatUtil.parse("/combat toggle &7- Enables/disables combat tagging."));
        sender.sendMessage(ChatUtil.parse("/combat update &7- Checks for and downloads plugin updates."));
        sender.sendMessage(ChatUtil.parse("/combat api &7- Shows API status."));
        sender.sendMessage(ChatUtil.parse("/combat protection &7- Shows your PvP protection time left."));
        sender.sendMessage(ChatUtil.parse("/" + disableCommand + " &7- Disables your newbie PvP protection."));
        sender.sendMessage(ChatUtil.parse("/combat help &7- Shows this help message."));
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