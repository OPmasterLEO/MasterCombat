package net.opmasterleo.combat.listener;

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
        if (player == null) return;
        Combat combat = Combat.getInstance();

        final boolean disableElytra = combat.isDisableElytra();
        final boolean enderPearlEnabled = combat.isEnderPearlEnabled();
        final boolean inCombat = combat.isInCombat(player);
        final boolean visualEffectsEnabled = combat.getConfig().getBoolean("CombatTagGlowing.Enabled", false);
        if (disableElytra && inCombat && visualEffectsEnabled) {
            if (player.isGliding() || player.isFlying()) {
                player.setGliding(false);
                player.setFlying(false);
                player.setAllowFlight(false);
                player.sendMessage(combat.getElytraDisabledMsg());
            }
        }

        if (enderPearlEnabled && inCombat && event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (from == null || to == null) return;

            double dx = from.getX() - to.getX();
            double dz = from.getZ() - to.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);

            long maxDistance = combat.getEnderPearlDistance();
            if (distance > maxDistance) {
                String prefix = combat.getMessage("Messages.Prefix");
                String msg = combat.getMessage("EnderPearl.Format");
                event.setCancelled(true);
                player.sendMessage(prefix + msg);
            }
        }
    }
}