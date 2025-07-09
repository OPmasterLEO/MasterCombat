package net.opmasterleo.combat.listener;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.manager.Update;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerJoinListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();
        
        if (combat.getGlowManager() != null) {
            combat.getGlowManager().trackPlayer(player);
        }
        
        if (player.isOp() && combat.getConfig().getBoolean("update-notify-chat", false)) {
            Update.notifyOnPlayerJoin(player, combat);
        }
    }
}