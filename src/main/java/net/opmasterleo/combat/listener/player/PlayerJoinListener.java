package net.opmasterleo.combat.listener.player;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.manager.Update;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {
    private final Combat plugin;
    private final boolean notifyUpdates;

    public PlayerJoinListener(Combat plugin) {
        this.plugin = plugin;
        this.notifyUpdates = plugin.getConfig().getBoolean("update-notify-chat", false);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.isEnabled()) return;
        
        Player player = event.getPlayer();
        if (plugin.getGlowManager() != null) {
            plugin.getGlowManager().trackPlayer(player);
        }

        if (notifyUpdates && player.isOp()) {
            Update.notifyOnPlayerJoin(player, plugin);
        }
    }
}