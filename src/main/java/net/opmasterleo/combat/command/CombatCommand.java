package net.opmasterleo.combat.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.listener.NewbieProtectionListener;
import net.opmasterleo.combat.manager.Update;
import net.opmasterleo.combat.util.ChatUtil;

public final class CombatCommand implements CommandExecutor, TabCompleter, Listener {

    private static boolean updateCheckInProgress = false;

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Combat combat = Combat.getInstance();
        combat.getCombatRecords().remove(player.getUniqueId());
        Player opponent = combat.getCombatOpponent(player);
        combat.getCombatRecords().remove(player.getUniqueId());
        if (combat.getGlowManager() != null) {
            combat.getGlowManager().setGlowing(player, false);
            if (opponent != null) {
                combat.getGlowManager().setGlowing(opponent, false);
            }
        }
        if (opponent != null) {
            combat.getCombatRecords().remove(opponent.getUniqueId());
            combat.getCombatRecords().remove(opponent.getUniqueId());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Combat combat = Combat.getInstance();
        if (combat == null || combat.getConfig() == null || combat.getPluginMeta() == null) {
            if (sender != null) {
                sender.sendMessage(ChatUtil.parse("&cError: Plugin not properly initialized."));
            }
            return true;
        }
        boolean newbieProtectionEnabled = combat.getConfig().getBoolean("NewbieProtection.enabled", true);
        NewbieProtectionListener protectionListener = combat.getNewbieProtectionListener();
        String disableCommand = Optional.ofNullable(combat.getConfig().getString("NewbieProtection.settings.disableCommand"))
            .orElse("removeprotect").toLowerCase();
        String pluginName = combat.getPluginMeta().getDisplayName();
        String pluginVersion = combat.getPluginMeta().getVersion();
        String pluginDescription = combat.getPluginMeta().getDescription();
        String cmdLabel = label.toLowerCase();

        if (!newbieProtectionEnabled) {
            if (cmdLabel.equals("protection") || cmdLabel.equals(disableCommand)) {
                return false;
            }
        }

        if (cmdLabel.equals("protection")) {
            if (sender == null || !(sender instanceof Player player)) {
                if (sender != null) {
                    sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                }
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
            if (sender == null || !(sender instanceof Player player)) {
                if (sender != null) {
                    sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                }
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

        if (cmdLabel.equals("visibility")) {
            if (sender == null || !(sender instanceof Player player)) {
                if (sender != null) sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                return true;
            }
            Combat combatInstance = Combat.getInstance();
            if (combatInstance != null) {
                boolean currentVisibility = combatInstance.isCombatVisible(player);
                combatInstance.setCombatVisibility(player, !currentVisibility);
                if (!currentVisibility) {
                    player.sendMessage(ChatUtil.parse("&aCombat visibility: &eON"));
                } else {
                    player.sendMessage(ChatUtil.parse("&cCombat visibility: &eOFF"));
                }
            }
            return true;
        }

        if (cmdLabel.equals("combat")) {
            if (args.length == 0) {
                String displayText = pluginName.contains(pluginVersion) ? pluginName : (pluginName + " v" + pluginVersion);
                sender.sendMessage(ChatUtil.parse("&b" + displayText));
                sender.sendMessage(ChatUtil.parse("&7" + pluginDescription));
                sender.sendMessage(ChatUtil.parse("&7Type &e/combat help &7for command list."));
                return true;
            }
            if (args[0].equalsIgnoreCase("help")) {
                sendHelp(sender, disableCommand);
                return true;
            }
            String cmd0 = args[0].toLowerCase();
            return switch (cmd0) {
                case "reload" -> {
                    if (sender == null) yield true;
                    long startTime = System.nanoTime();
                    combat.reloadCombatConfig();
                    long endTime = System.nanoTime();
                    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                    sender.sendMessage(ChatUtil.parse("&aConfig reloaded in " + durationMs + "ms!"));
                    yield true;
                }
                
                case "toggle" -> {
                    if (sender == null) yield true;
                    Combat combatInstance = Combat.getInstance();
                    if (combatInstance != null) {
                        combatInstance.setCombatEnabled(!combatInstance.isCombatEnabled());
                        sender.sendMessage(ChatUtil.parse("&eCombat has been &" +
                            (combatInstance.isCombatEnabled() ? "aenabled" : "cdisabled") + "&e."));
                    }
                    yield true;
                }
                
                case "visibility" -> {
                    if (sender == null) yield true;
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                        yield true;
                    }
                    Combat combatInstance = Combat.getInstance();
                    if (combatInstance != null) {
                        boolean currentVisibility = combatInstance.isCombatVisible(player);
                        combatInstance.setCombatVisibility(player, !currentVisibility);
                        if (!currentVisibility) {
                            player.sendMessage(ChatUtil.parse("&aCombat visibility: &eON"));
                        } else {
                            player.sendMessage(ChatUtil.parse("&cCombat visibility: &eOFF"));
                        }
                    }
                    yield true;
                }
                
                case "removeprotect", "protection" -> {
                    if (sender == null) yield true;
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                        yield true;
                    }
                    if (args[0].equalsIgnoreCase(disableCommand)) {
                        if (protectionListener == null || !protectionListener.isActuallyProtected(player)) {
                            player.sendMessage(ChatUtil.parse("&cYou are not protected."));
                            yield true;
                        }
                        player.sendMessage(ChatUtil.parse("&eAre you sure you want to remove your protection? &cRun '/" +
                            disableCommand + " confirm' to confirm."));
                        yield true;
                    }
                    yield true;
                }
                
                case "confirm" -> {
                    if (sender == null) yield true;
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(ChatUtil.parse("&cOnly players can use this command."));
                        yield true;
                    }
                    if (protectionListener == null || !protectionListener.isActuallyProtected(player)) {
                        player.sendMessage(ChatUtil.parse("&cYou don't have active protection."));
                        yield true;
                    }
                    protectionListener.removeProtection(player);
                    yield true;
                }
                
                case "update" -> {
                    if (sender == null) yield true;
                    if (updateCheckInProgress) {
                        sender.sendMessage(ChatUtil.parse("&eUpdate check is already in progress. Please wait..."));
                        yield true;
                    }
                    updateCheckInProgress = true;
                    Combat plugin = Combat.getInstance();
                    if (plugin != null) {
                        Update.checkForUpdates(plugin, sender);
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            updateCheckInProgress = false;
                            if (!Update.isUpdateFound()) {
                                Update.setUpdateFound(true);
                            } else {
                                Update.downloadAndReplaceJar(plugin, sender);
                                Update.setUpdateFound(false);
                            }
                        }, 40L);
                    }
                    yield true;
                }
                
                default -> {
                    if (sender != null) {
                        sender.sendMessage(ChatUtil.parse("&cUnknown command. Type &e/combat help &cfor usage."));
                    }
                    yield true;
                }
            };
        }
        return true;
    }

    private void sendHelp(CommandSender sender, String disableCommand) {
        Combat combat = Combat.getInstance();
        boolean newbieProtectionEnabled = combat.getConfig().getBoolean("NewbieProtection.enabled", true);
        sender.sendMessage(ChatUtil.parse("&eMasterCombat Command List:"));
        sender.sendMessage(ChatUtil.parse("/combat reload &7- Reloads the plugin configuration."));
        sender.sendMessage(ChatUtil.parse("/combat toggle &7- Enables/disables combat tagging."));
    sender.sendMessage(ChatUtil.parse("/visibility &7- Toggle combat UI visibility (messages/timer)."));
        sender.sendMessage(ChatUtil.parse("/combat update &7- Checks for and downloads plugin updates."));
        if (newbieProtectionEnabled) {
            sender.sendMessage(ChatUtil.parse("/protection &7- Shows your PvP protection time left."));
            sender.sendMessage(ChatUtil.parse("/" + disableCommand + " &7- Disables your newbie PvP protection."));
        }
        sender.sendMessage(ChatUtil.parse("/combat help &7- Shows this help message."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        Combat combat = Combat.getInstance();
        if (combat == null || combat.getConfig() == null) {
            if (sender != null) {
                sender.sendMessage(ChatUtil.parse("&cError: Plugin not properly initialized."));
            }
            return Collections.emptyList();
        }
        boolean newbieProtectionEnabled = combat.getConfig().getBoolean("NewbieProtection.enabled", true);
        String configValue = combat.getConfig().getString("NewbieProtection.settings.disableCommand");
        String disableCommand = (configValue != null ? configValue : "removeprotect").toLowerCase();
        if (args.length == 1) {
            if ("reload".startsWith(args[0].toLowerCase())) {
                completions.add("reload");
            }
            if ("toggle".startsWith(args[0].toLowerCase())) {
                completions.add("toggle");
            }
            if ("visibility".startsWith(args[0].toLowerCase())) {
                completions.add("visibility");
            }
            if ("update".startsWith(args[0].toLowerCase())) {
                completions.add("update");
            }
            if (newbieProtectionEnabled && "protection".startsWith(args[0].toLowerCase())) {
                completions.add("protection");
            }
            if (newbieProtectionEnabled && disableCommand.startsWith(args[0].toLowerCase())) {
                completions.add(disableCommand);
            }
        }
        return completions;
    }
}