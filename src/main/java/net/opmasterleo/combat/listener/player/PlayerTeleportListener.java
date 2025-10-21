package net.opmasterleo.combat.listener.player;

import net.opmasterleo.combat.Combat;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class PlayerTeleportListener implements Listener {

    @EventHandler
    public void handle(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();

        final boolean disableElytra = combat.isDisableElytra();
        final boolean enderPearlEnabled = combat.isEnderPearlEnabled();
        final boolean inCombat = combat.isInCombat(player);
        if (disableElytra && inCombat) {
            if (player.isGliding() || player.isFlying()) {
                player.setGliding(false);
                player.setFlying(false);
                player.setAllowFlight(false);
                if (combat.getElytraDisabledMsg() != null && !combat.getElytraDisabledMsg().isEmpty()) {
                    combat.sendCombatMessage(player, combat.getElytraDisabledMsg(), combat.getElytraDisabledType());
                }
            }
        }

        if (enderPearlEnabled && inCombat && event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            Location from = event.getFrom();
            Location to = event.getTo();

            double dx = from.getX() - to.getX();
            double dz = from.getZ() - to.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);

            long maxDistance = combat.getEnderPearlDistance();
            if (distance > maxDistance) {
                String prefix = combat.getMessage("Messages.Prefix");
                String msg = combat.getMessage("enderpearl.message");
                if (msg == null || msg.isEmpty()) {
                    msg = combat.getMessage("EnderPearl.Format");
                }
                event.setCancelled(true);
                player.sendMessage(prefix + msg);
                combat.debug("Cancelled ender pearl teleport: distance " + distance + " > " + maxDistance);
            }
        }
    }
}