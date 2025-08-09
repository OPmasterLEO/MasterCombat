package net.opmasterleo.combat.listener.player;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class PlayerMoveListener implements PacketListener {

    private static volatile boolean packetEventsEnabled = true;

    public static void disablePacketEventsIntegration() {
        packetEventsEnabled = false;
    }

    private final Combat plugin;
    private final boolean disableElytra;
    private final boolean visualEffectsEnabled;
    private final String elytraDisabledMsg;

    public PlayerMoveListener(Combat plugin) {
        this.plugin = plugin;
        this.disableElytra = plugin.isDisableElytra();
        this.visualEffectsEnabled = plugin.getConfig().getBoolean("CombatTagGlowing.Enabled", false);
        this.elytraDisabledMsg = plugin.getElytraDisabledMsg();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!packetEventsEnabled) return;
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_ABILITIES) {
            handleAbilitiesPacket(event);
        } else if (isMovementPacket(type)) {
            handleMovementPacket(event);
        }
    }

    private boolean isMovementPacket(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION
            || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
            || type == PacketType.Play.Client.PLAYER_ROTATION;
    }

    private void handleAbilitiesPacket(PacketReceiveEvent event) {
        if (!disableElytra || !visualEffectsEnabled) return;

        User user = event.getUser();
        Player player = Bukkit.getPlayer(user.getUUID());
        if (player == null || !player.isOnline()) return;

        if (!plugin.isCombatEnabledInWorld(player) || !plugin.isInCombat(player)) return;

        if (player.isFlying() || player.getAllowFlight()) {
            event.setCancelled(true);
            player.setFlying(false);
            player.setAllowFlight(false);
            if (elytraDisabledMsg != null && !elytraDisabledMsg.isEmpty()) {
                player.sendMessage(elytraDisabledMsg);
            }
        }
        if (player.isGliding()) {
            event.setCancelled(true);
            player.setGliding(false);
        }
    }

    private void handleMovementPacket(PacketReceiveEvent event) {
        User user = event.getUser();
        Player player = Bukkit.getPlayer(user.getUUID());
        if (player == null || !player.isOnline()) return;

        if (!plugin.isCombatEnabledInWorld(player) || !plugin.isInCombat(player)) return;

        SchedulerUtil.runTask(plugin, () -> restrictPlayerMovement(player));
    }

    private void restrictPlayerMovement(Player player) {
        if (!disableElytra || !plugin.isInCombat(player)) return;
        if (!visualEffectsEnabled) return;

        if (player.isGliding()) {
            player.setGliding(false);
        }
        if (player.getAllowFlight() || player.isFlying()) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!packetEventsEnabled) return;
    }
}