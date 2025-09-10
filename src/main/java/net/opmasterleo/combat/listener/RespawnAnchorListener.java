package net.opmasterleo.combat.listener;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import net.opmasterleo.combat.Combat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.inventory.EquipmentSlot;
import net.opmasterleo.combat.util.SchedulerUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RespawnAnchorListener implements Listener, PacketListener {
    private final Combat plugin;
    private final Map<Block, UUID> anchorActivators = new ConcurrentHashMap<>();
    private final Map<Block, Long> activationTimestamps = new ConcurrentHashMap<>();
    private final Map<Location, ExplosionData> explosionCache = new ConcurrentHashMap<>();
    private static final long ACTIVATION_TIMEOUT_MS = 5000L;
    private static final long EXPLOSION_CACHE_TIMEOUT_MS = 10000L;

    private static class ExplosionData {
        final UUID activatorId;
        final long timestamp;

        ExplosionData(UUID activatorId) {
            this.activatorId = activatorId;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public RespawnAnchorListener(Combat plugin) {
        this.plugin = plugin;
        plugin.debug("RespawnAnchorListener initialized");
        try {
            SchedulerUtil.runTask(plugin, () -> {
                try {
                    if (plugin.isPacketEventsAvailable()) {
                        plugin.safelyRegisterPacketListener(this);
                        plugin.debug("RespawnAnchorListener registered with PacketEvents");
                    } else {
                        plugin.debug("PacketEvents not available for RespawnAnchorListener; using Bukkit fallbacks");
                    }
                } catch (Exception e) {
                    plugin.debug("Error registering RespawnAnchorListener packet listener: " + e.getMessage());
                }
            });
        } catch (IllegalArgumentException e) {
            plugin.debug("Error scheduling RespawnAnchorListener packet listener registration: " + e.getMessage());
        }
        SchedulerUtil.runTaskTimerAsync(plugin, this::cleanupExpiredData, 1200L, 1200L);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        try {
            if (!plugin.isPluginEnabled() || !plugin.isEnabled()) return;

            if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
                WrapperPlayClientUseItem use = new WrapperPlayClientUseItem(event);
                if (use.getHand() != InteractionHand.MAIN_HAND) return;
                Player player = (Player) event.getPlayer();
                SchedulerUtil.runTask(plugin, () -> {
                    if (!plugin.isEnabled()) return;
                    Block target = player.getTargetBlockExact(5);
                    if (target == null || target.getType() != Material.RESPAWN_ANCHOR) return;
                    trackAnchorInteraction(target, player);
                    plugin.debug("PacketEvents: tracked anchor interaction by " + player.getName() + " at " + target.getLocation());
                });
            } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                WrapperPlayClientPlayerBlockPlacement placement = new WrapperPlayClientPlayerBlockPlacement(event);
                Player player = (Player) event.getPlayer();
                Vector3i pos = placement.getBlockPosition();
                SchedulerUtil.runTask(plugin, () -> {
                    if (!plugin.isEnabled()) return;
                    Block block = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ()).getBlock();
                    if (block.getType() != Material.RESPAWN_ANCHOR) return;
                    trackAnchorInteraction(block, player);
                    plugin.debug("PacketEvents: tracked anchor placed by " + player.getName() + " at " + block.getLocation());
                });
            }
        } catch (IllegalArgumentException e) {
            if (plugin.isEnabled()) plugin.debug("Error in RespawnAnchorListener.onPacketReceive: " + e.getMessage());
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            if (!plugin.isPluginEnabled() || !plugin.isEnabled()) return;

            if (event.getPacketType() == PacketType.Play.Server.EXPLOSION) {
                WrapperPlayServerExplosion explosion = new WrapperPlayServerExplosion(event);
                Player sample = (Player) event.getPlayer();
                Vector3d pos = explosion.getPosition();
                Location explosionLoc = new Location(sample.getWorld(), pos.getX(), pos.getY(), pos.getZ());

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) return;
                    Block nearest = findNearestAnchorBlock(explosionLoc, 3.0);
                    if (nearest == null) return;
                    UUID activator = anchorActivators.get(nearest);
                    if (activator != null) {
                        explosionCache.put(explosionLoc, new ExplosionData(activator));
                        plugin.debug("PacketEvents: tracked anchor explosion at " + explosionLoc + " by " + activator);
                        SchedulerUtil.runTaskLater(plugin, () -> explosionCache.remove(explosionLoc), 4L);
                    }
                });
            }
        } catch (IllegalArgumentException e) {
            if (plugin.isEnabled()) plugin.debug("Error in RespawnAnchorListener.onPacketSend: " + e.getMessage());
        }
    }

    private Block findNearestAnchorBlock(Location loc, double radius) {
        if (loc == null || loc.getWorld() == null) return null;
        int r = (int) Math.ceil(radius);
        Block nearest = null;
        double best = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = loc.getWorld().getBlockAt(loc.getBlockX() + x, loc.getBlockY() + y, loc.getBlockZ() + z);
                    if (b.getType() == Material.RESPAWN_ANCHOR) {
                        double d = b.getLocation().distanceSquared(loc);
                        if (d < best) { best = d; nearest = b; }
                    }
                }
            }
        }
        return nearest;
    }

    // Bukkit fallback: only used when PacketEvents not available (kept for compatibility)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (plugin.isPacketEventsAvailable()) return; // packet path preferred
        if (!isEnabled()) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.RESPAWN_ANCHOR) return;
        Player player = event.getPlayer();
        if (shouldBypass(player)) return;

        plugin.debug("Bukkit fallback: Player " + player.getName() + " interacting with respawn anchor at " + block.getLocation());
        NewbieProtectionListener protectionListener = plugin.getNewbieProtectionListener();
        if (protectionListener != null && protectionListener.isActuallyProtected(player)) {
            boolean isDangerousDimension = player.getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER;
            if (isDangerousDimension) {
                for (Entity nearby : player.getNearbyEntities(6.0, 6.0, 6.0)) {
                    if (nearby instanceof Player target && !player.getUniqueId().equals(target.getUniqueId())) {
                        event.setCancelled(true);
                        protectionListener.sendBlockedMessage(player, protectionListener.getAnchorBlockMessage());
                        plugin.debug("Blocking anchor interaction due to newbie protection");
                        return;
                    }
                }
            }
        }

        trackAnchorInteraction(block, player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        if (event.getCause() != DamageCause.BLOCK_EXPLOSION &&
            event.getCause() != DamageCause.ENTITY_EXPLOSION) {
            return;
        }

        Location damageLocation = victim.getLocation();
        Player activator = findActivatorForDamage(damageLocation);
        if (activator != null && !shouldBypass(activator)) {
            if (plugin.getSuperVanishManager() != null && plugin.getSuperVanishManager().isVanished(activator)) {
                plugin.debug("Skipping combat tag: activator is vanished");
                return;
            }

            NewbieProtectionListener protection = plugin.getNewbieProtectionListener();
            if (protection != null) {
                boolean activatorProtected = protection.isActuallyProtected(activator);
                boolean victimProtected = protection.isActuallyProtected(victim);
                if (activatorProtected || victimProtected) {
                    plugin.debug("Skipping combat tag: newbie protection active");
                    return;
                }
            }

            boolean selfCombat = plugin.getConfig().getBoolean("self-combat", false);
            if (activator.getUniqueId().equals(victim.getUniqueId())) {
                if (selfCombat) {
                    plugin.directSetCombat(activator, activator);
                    plugin.debug("Self-combat applied from anchor explosion");
                }
            } else {
                plugin.directSetCombat(activator, victim);
                plugin.directSetCombat(victim, activator);
                plugin.debug("Combat tagged from anchor explosion: " +
                    activator.getName() + " <-> " + victim.getName());
            }

            if (victim.getHealth() <= event.getFinalDamage()) {
                victim.setKiller(activator);
                plugin.debug("Set killer for lethal damage: " + activator.getName());
            }
        }
    }

    private Player findActivatorForDamage(Location damageLocation) {
        for (Map.Entry<Location, ExplosionData> entry : explosionCache.entrySet()) {
            if (isSameWorld(entry.getKey(), damageLocation) &&
                entry.getKey().distanceSquared(damageLocation) <= 100) {

                UUID activatorId = entry.getValue().activatorId;
                return Bukkit.getPlayer(activatorId);
            }
        }

        for (Map.Entry<Block, UUID> entry : anchorActivators.entrySet()) {
            Block block = entry.getKey();
            if (isSameWorld(block.getLocation(), damageLocation) &&
                block.getLocation().distanceSquared(damageLocation) <= 100) {

                return Bukkit.getPlayer(entry.getValue());
            }
        }

        return null;
    }

    private boolean isSameWorld(Location loc1, Location loc2) {
        return loc1.getWorld() != null && loc1.getWorld().equals(loc2.getWorld());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!isEnabled()) return;
        for (Block block : event.blockList()) {
            if (block.getType() == Material.RESPAWN_ANCHOR) {
                UUID activatorId = anchorActivators.remove(block);
                activationTimestamps.remove(block);
                if (activatorId != null) {
                    explosionCache.put(event.getLocation(), new ExplosionData(activatorId));
                    plugin.debug("Tracked explosion at " + event.getLocation() +
                        " by activator " + activatorId);

                    SchedulerUtil.runTaskLaterAsync(plugin, () -> explosionCache.remove(event.getLocation()), 100L);

                    break;
                }
            }
        }
        cleanupExpiredData();
    }

    private void cleanupExpiredData() {
        long now = System.currentTimeMillis();
        activationTimestamps.entrySet().removeIf(e -> now - e.getValue() > ACTIVATION_TIMEOUT_MS);
        anchorActivators.keySet().retainAll(activationTimestamps.keySet());

        explosionCache.entrySet().removeIf(e -> now - e.getValue().timestamp > EXPLOSION_CACHE_TIMEOUT_MS);
        if (plugin.isDebugEnabled()) {
            plugin.debug("RespawnAnchorListener cleanupExpiredData: activations=" + anchorActivators.size() +
                ", explosions=" + explosionCache.size());
        }
    }

    public void trackAnchorInteraction(Block block, Player player) {
        if (!isEnabled() || block == null || block.getType() != Material.RESPAWN_ANCHOR || player == null) return;
        anchorActivators.put(block, player.getUniqueId());
        activationTimestamps.put(block, System.currentTimeMillis());
        block.setMetadata("anchor_activator_uuid", new FixedMetadataValue(plugin, player.getUniqueId()));
        plugin.debug("Tracked anchor interaction: " + player.getName() + " at " + block.getLocation());
    }

    public void registerPotentialExplosion(Location location, Player player) {
        if (!isEnabled() || location == null || player == null) return;
        explosionCache.put(location, new ExplosionData(player.getUniqueId()));
        plugin.debug("Registered potential explosion at " + location + " by " + player.getName());
        SchedulerUtil.runTaskLaterAsync(plugin, () -> explosionCache.remove(location), 100L);
    }

    private boolean shouldBypass(Player player) {
        return player == null ||
            player.getGameMode() == GameMode.CREATIVE ||
            player.getGameMode() == GameMode.SPECTATOR;
    }

    private boolean isEnabled() {
        return plugin.isCombatEnabled() &&
            plugin.getConfig().getBoolean("link-respawn-anchor", true);
    }

    public Player getAnchorActivator(UUID anchorId) {
        for (Map.Entry<Block, UUID> entry : anchorActivators.entrySet()) {
            Block block = entry.getKey();
            if (UUID.nameUUIDFromBytes(block.getLocation().toString().getBytes()).equals(anchorId)) {
                return Bukkit.getPlayer(entry.getValue());
            }
        }
        return null;
    }

    public void cleanup() {
        anchorActivators.clear();
        activationTimestamps.clear();
        explosionCache.clear();
        plugin.debug("RespawnAnchorListener cleanup complete");
    }
}