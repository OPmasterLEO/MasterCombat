package net.opmasterleo.combat.listener;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BedExplosionListener implements Listener, PacketListener {

    private final Map<UUID, Player> recentBedInteractions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> interactionTimestamps = new ConcurrentHashMap<>();
    private static final long INTERACTION_TIMEOUT = 5000;

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.USE_ITEM) return;
        
        WrapperPlayClientUseItem useItemPacket = new WrapperPlayClientUseItem(event);
        if (useItemPacket.getHand() != InteractionHand.MAIN_HAND) return;
        
        Player player = (Player) event.getPlayer();
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
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != DamageCause.BLOCK_EXPLOSION) return;
        Combat combat = Combat.getInstance();
        if (!combat.getConfig().getBoolean("link-bed-explosions", true)) return;
        Location victimLoc = victim.getLocation();
        World world = victim.getWorld();
        int radius = 6;
        int radiusSquared = radius * radius;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radiusSquared) continue;
                
                    Block block = world.getBlockAt(victimLoc.getBlockX() + x, 
                                                 victimLoc.getBlockY() + y, 
                                                 victimLoc.getBlockZ() + z);
                    if (!block.getType().name().endsWith("_BED")) continue;
                    Location bedLoc = block.getLocation();
                    UUID bedId = UUID.nameUUIDFromBytes(bedLoc.toString().getBytes());
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
                        
                        if (victim.getHealth() <= event.getFinalDamage()) {
                            victim.setKiller(activator);
                        }
                        return;
                    }
                }
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