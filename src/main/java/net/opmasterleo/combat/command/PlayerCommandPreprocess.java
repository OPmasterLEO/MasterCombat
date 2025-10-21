package net.opmasterleo.combat.command;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;

public final class PlayerCommandPreprocess implements Listener {

    private Set<String> blockedCommands;
    private String sound;
    private boolean soundEnabled;

    public PlayerCommandPreprocess() {
        reloadBlockedCommands();
    }

    public void reloadBlockedCommands() {
        blockedCommands = new CopyOnWriteArraySet<>();
        for (String cmd : Combat.getInstance().getConfig().getStringList("Commands.Blocked")) {
            blockedCommands.add(cmd.toLowerCase(Locale.ROOT).trim());
        }

        sound = Combat.getInstance().getConfig().getString("Commands.Sound", "entity.villager.no");
        soundEnabled = sound != null && !sound.isEmpty() && !sound.equalsIgnoreCase("none");
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
            String formatted = format
                    .replace("%mastercombat_command%", baseCommand)
                    .replace("%command%", baseCommand);
            Component message = ChatUtil.parse(prefix + formatted);
            player.sendMessage(message);

            if (soundEnabled) {
                try {
                    Sound soundEnum = Sound.sound(
                            Key.key(sound.toLowerCase(Locale.ROOT)),
                            Sound.Source.MASTER,
                            1.0f,
                            1.0f
                    );
                    player.playSound(soundEnum);
                } catch (Exception e) {
                    player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
                }
            }
        }
    }
}