package net.opmasterleo.combat.listener;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import net.opmasterleo.combat.Combat;

public class BedExplosionListener implements PacketListener, Listener {

    private static volatile boolean packetEventsEnabled = true;

    public static void disablePacketEventsIntegration() {
        packetEventsEnabled = false;
    }

    private final ConcurrentLong2ReferenceChainedHashTable<Player> recentBedInteractions = ConcurrentLong2ReferenceChainedHashTable.createWithExpected(64, 0.75f);
    private final ConcurrentLong2ReferenceChainedHashTable<Long> interactionTimestamps = ConcurrentLong2ReferenceChainedHashTable.createWithExpected(64, 0.75f);

    public BedExplosionListener() {
    }

    public void initialize(Combat plugin) {
        if (plugin == null) return;
        try {
            if (plugin.isPacketEventsAvailable()) {
                plugin.safelyRegisterPacketListener(this);
            }
        } catch (Exception ignored) {
        }
        startPeriodicCleanup(plugin);
    }

    private void startPeriodicCleanup(Combat plugin) {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            long expiryThreshold = now - 10000L;
            int removed = 0;
            for (long key = 0; key < Long.MAX_VALUE && removed < 100; key++) {
                Long timestamp = interactionTimestamps.get(key);
                if (timestamp != null && timestamp < expiryThreshold) {
                    interactionTimestamps.remove(key);
                    recentBedInteractions.remove(key);
                    removed++;
                }
            }
        }, 200L, 200L);
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
            World.Environment dimension = player.getWorld().getEnvironment();
            if (dimension != World.Environment.NETHER && dimension != World.Environment.THE_END) return;
            Block targetBlock = player.getTargetBlockExact(5);
            if (targetBlock == null) return;

            String blockTypeName = targetBlock.getType().name();
            if (!blockTypeName.endsWith("_BED")) return;

            Location bedLocation = targetBlock.getLocation();
            long bedKey = ((long)bedLocation.getBlockX() << 40) | ((long)bedLocation.getBlockY() << 20) | bedLocation.getBlockZ();
            recentBedInteractions.put(bedKey, player);
            long currentTime = System.currentTimeMillis();
            interactionTimestamps.put(bedKey, currentTime);
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
        long bedKey = ((long)explosionLocation.getBlockX() << 40) | ((long)explosionLocation.getBlockY() << 20) | explosionLocation.getBlockZ();
        Player activator = recentBedInteractions.get(bedKey);

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
        long bedKey = Combat.uuidToLong(bedId);
        return recentBedInteractions.get(bedKey);
    }

    public void cleanup() {
        recentBedInteractions.clear();
        interactionTimestamps.clear();
    }

    public void registerPotentialExplosion(Location location, Player player) {
        if (location == null || player == null) return;
        long bedKey = ((long)location.getBlockX() << 40) | ((long)location.getBlockY() << 20) | location.getBlockZ();
        recentBedInteractions.put(bedKey, player);
        interactionTimestamps.put(bedKey, System.currentTimeMillis());
    }
}