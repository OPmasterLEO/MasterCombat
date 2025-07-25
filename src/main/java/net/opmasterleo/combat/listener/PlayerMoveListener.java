package net.opmasterleo.combat.listener;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;

public final class PlayerMoveListener implements Listener, PacketListener {

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION ||
            event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION ||
            event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION) {
            
            Player player = (Player) event.getPlayer();
            Combat combat = Combat.getInstance();
            
            if (!combat.isCombatEnabledInWorld(player) || !combat.isInCombat(player)) {
                return;
            }

            SchedulerUtil.runTask(combat, () -> {
                if (player != null && player.isOnline()) {
                    restrictPlayerMovement(player);
                }
            });
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
    }

    private void restrictPlayerMovement(Player player) {
        Combat combat = Combat.getInstance();
        if (!combat.isDisableElytra() || !combat.isInCombat(player)) return;

        boolean visualEffectsEnabled = combat.getConfig().getBoolean("CombatTagGlowing.Enabled", false);
        if (!visualEffectsEnabled) return;
        
        if (player.isGliding()) {
            player.setGliding(false);
        }
        
        if (player.getAllowFlight() || player.isFlying()) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    @EventHandler
    public void onElytraToggle(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();
        
        if (!combat.isInCombat(player) || !combat.isDisableElytra()) return;

        boolean visualEffectsEnabled = combat.getConfig().getBoolean("CombatTagGlowing.Enabled", false);
        if (!visualEffectsEnabled) return;
        
        if (event.isFlying()) {
            event.setCancelled(true);
            player.sendMessage(combat.getElytraDisabledMsg());
        }
    }
}