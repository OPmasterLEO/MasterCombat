package net.opmasterleo.combat.listener;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.util.Vector3d;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BedExplosionListener implements PacketListener, Listener {

    private static volatile boolean packetEventsEnabled = true;

    public static void disablePacketEventsIntegration() {
        packetEventsEnabled = false;
    }

    private final Map<UUID, Player> recentBedInteractions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> interactionTimestamps = new ConcurrentHashMap<>();
    private static final long INTERACTION_TIMEOUT = 5000L;

    public BedExplosionListener() {
        Combat combat = Combat.getInstance();
        if (combat != null && combat.isPacketEventsAvailable()) {
            combat.safelyRegisterPacketListener(this);
        }
    }

    public void initialize(Combat plugin) {
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!packetEventsEnabled) return;
        if (event.getPacketType() != PacketType.Play.Client.USE_ITEM) return;

        try {
            WrapperPlayClientUseItem useItemPacket = new WrapperPlayClientUseItem(event);
            if (useItemPacket.getHand() != InteractionHand.MAIN_HAND) return;

            Object pktPlayer = event.getPlayer();
            if (!(pktPlayer instanceof Player)) return;
            Player player = (Player) pktPlayer;
            Block targetBlock = player.getTargetBlockExact(5);
            if (targetBlock == null) return;

            String blockTypeName = targetBlock.getType().name();
            if (!blockTypeName.endsWith("_BED")) return;

            World.Environment dimension = player.getWorld().getEnvironment();
            if (dimension != World.Environment.NETHER && dimension != World.Environment.THE_END) return;

            Location bedLocation = targetBlock.getLocation();
            UUID bedId = UUID.nameUUIDFromBytes(bedLocation.toString().getBytes());
            recentBedInteractions.put(bedId, player);
            interactionTimestamps.put(bedId, System.currentTimeMillis());
            SchedulerUtil.runTaskLaterAsync(Combat.getInstance(), () -> {
                long now = System.currentTimeMillis();
                interactionTimestamps.entrySet().removeIf(entry ->
                    now - entry.getValue() > INTERACTION_TIMEOUT);
                recentBedInteractions.keySet().retainAll(interactionTimestamps.keySet());
            }, 200L);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!packetEventsEnabled) return;
        if (event.getPacketType() != PacketType.Play.Server.EXPLOSION) return;

        try {
            WrapperPlayServerExplosion explosionPacket = new WrapperPlayServerExplosion(event);
            Object pktPlayer = event.getPlayer();
            if (!(pktPlayer instanceof Player)) return;
            Player player = (Player) pktPlayer;

            Vector3d position = explosionPacket.getPosition();
            Location explosionLocation = new Location(
                player.getWorld(),
                position.getX(),
                position.getY(),
                position.getZ()
            );

            handleExplosion(player, explosionLocation);
        } catch (Throwable ignored) {}
    }

    private void handleExplosion(Player victim, Location explosionLocation) {
        Combat combat = Combat.getInstance();
        if (!combat.getConfig().getBoolean("link-bed-explosions", true)) return;

        UUID bedId = UUID.nameUUIDFromBytes(explosionLocation.toString().getBytes());
        Player activator = recentBedInteractions.get(bedId);

        if (activator != null && activator.isOnline()) {
            if (combat.getSuperVanishManager() != null &&
                combat.getSuperVanishManager().isVanished(activator)) {
                return;
            }

            NewbieProtectionListener protection = combat.getNewbieProtectionListener();
            if (protection != null) {
                boolean activatorProtected = protection.isActuallyProtected(activator);
                boolean victimProtected = protection.isActuallyProtected(victim);
                if (activatorProtected || victimProtected) {
                    return;
                }
            }

            if (activator.getUniqueId().equals(victim.getUniqueId())) {
                if (combat.getConfig().getBoolean("self-combat", false)) {
                    combat.directSetCombat(victim, victim);
                }
            } else {
                combat.directSetCombat(victim, activator);
                combat.directSetCombat(activator, victim);
            }
        }
    }

    public Player getBedActivator(UUID bedId) {
        return recentBedInteractions.get(bedId);
    }

    public void cleanup() {
        recentBedInteractions.clear();
        interactionTimestamps.clear();
    }

    public void registerPotentialExplosion(Location location, Player player) {
        if (location == null || player == null) return;
        UUID bedId = UUID.nameUUIDFromBytes(location.toString().getBytes());
        recentBedInteractions.put(bedId, player);
        interactionTimestamps.put(bedId, System.currentTimeMillis());
        SchedulerUtil.runTaskLaterAsync(Combat.getInstance(), () -> {
            long now = System.currentTimeMillis();
            interactionTimestamps.entrySet().removeIf(entry ->
                now - entry.getValue() > INTERACTION_TIMEOUT);
            recentBedInteractions.keySet().retainAll(interactionTimestamps.keySet());
        }, 200L);
    }
}