package net.opmasterleo.combat.command;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.kyori.adventure.text.Component;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;

public final class PlayerCommandPreprocess implements Listener {

    private Set<String> blockedCommands;

    public PlayerCommandPreprocess() {
        reloadBlockedCommands();
    }

    public void reloadBlockedCommands() {
        blockedCommands = new CopyOnWriteArraySet<>();
        for (String cmd : Combat.getInstance().getConfig().getStringList("Commands.Blocked")) {
            blockedCommands.add(cmd.toLowerCase(Locale.ROOT).trim());
        }
    }

    @EventHandler
    public void handle(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!Combat.getInstance().isInCombat(player)) return;
        String command = event.getMessage().substring(1).trim();
        String baseCommand = command.split(" ")[0].toLowerCase(Locale.ROOT);
        if (blockedCommands.contains(baseCommand)) {
            event.setCancelled(true);
            String prefix = Combat.getInstance().getMessage("Messages.Prefix");
            String format = Combat.getInstance().getMessage("Commands.Format");
            Component message = ChatUtil.parse(prefix + format.replace("%command%", baseCommand));
            player.sendMessage(message);
        }
    }
}