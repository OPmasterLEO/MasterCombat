package net.opmasterleo.combat.listener;

import net.opmasterleo.combat.Combat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RespawnAnchor;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RespawnAnchorListener implements Listener {
    private final Combat plugin;
    private final Map<Block, UUID> anchorActivators = new ConcurrentHashMap<>();
    private final Map<Location, ExplosionData> explosionCache = new ConcurrentHashMap<>();
    
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
        // Start cleanup task
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanupExpiredData, 1200L, 1200L);
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!isEnabled()) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        Material blockType = block.getType();
        boolean isAnchor = blockType == Material.RESPAWN_ANCHOR;
        boolean isBed = blockType.name().endsWith("_BED");
        
        if (!isAnchor && !isBed) return;

        Player player = event.getPlayer();
        if (shouldBypass(player)) return;
        
        plugin.debug("Player " + player.getName() + " interacting with " + 
                    (isAnchor ? "respawn anchor" : "bed") + " at " + block.getLocation());
        
        NewbieProtectionListener protectionListener = plugin.getNewbieProtectionListener();
        if (protectionListener != null && protectionListener.isActuallyProtected(player)) {
            boolean isDangerousDimension = false;
            
            if (isAnchor && player.getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER) {
                isDangerousDimension = true;
            }
            
            if (isBed && (player.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER || 
                          player.getWorld().getEnvironment() == org.bukkit.World.Environment.THE_END)) {
                isDangerousDimension = true;
            }

            if (isDangerousDimension) {
                for (Entity nearby : player.getNearbyEntities(6.0, 6.0, 6.0)) {
                    if (nearby instanceof Player target && !player.getUniqueId().equals(target.getUniqueId())) {
                        event.setCancelled(true);
                        protectionListener.sendBlockedMessage(player, protectionListener.getAnchorBlockMessage());
                        plugin.debug("Blocking anchor/bed interaction due to newbie protection");
                        return;
                    }
                }
            }
        }

        if (isAnchor) {
            anchorActivators.put(block, player.getUniqueId());
            block.setMetadata("anchor_activator_uuid", 
                new FixedMetadataValue(plugin, player.getUniqueId()));
            plugin.debug("Registered anchor activator: " + player.getName() + " at " + block.getLocation());

            BlockData data = block.getBlockData();
            if (data instanceof RespawnAnchor anchor && 
                anchor.getCharges() > 0 && 
                !player.getInventory().getItemInMainHand().getType().equals(Material.GLOWSTONE)) {
                if (plugin.getConfig().getBoolean("self-combat", false)) {
                    plugin.directSetCombat(player, player);
                    plugin.debug("Self-combat enabled: Tagged player from anchor interaction");
                }
            }
        }
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
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!isEnabled()) return;
        for (Block block : event.blockList()) {
            if (block.getType() == Material.RESPAWN_ANCHOR) {
                UUID activatorId = anchorActivators.remove(block);
                if (activatorId != null) {
                    explosionCache.put(event.getLocation(), new ExplosionData(activatorId));
                    plugin.debug("Tracked explosion at " + event.getLocation() + 
                               " by activator " + activatorId);
                    
                    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                        explosionCache.remove(event.getLocation());
                    }, 100L);
                    
                    break;
                }
            }
        }
        cleanupExpiredData();
    }
    
    private void cleanupExpiredData() {
        if (explosionCache.size() > 100) {
            long now = System.currentTimeMillis();
            explosionCache.entrySet().removeIf(entry -> 
                now - entry.getValue().timestamp > 10000);
            plugin.debug("Cleaned up explosion cache, new size: " + explosionCache.size());
        }
    }
    
    public void trackAnchorInteraction(Block block, Player player) {
        if (!isEnabled() || block == null || block.getType() != Material.RESPAWN_ANCHOR || player == null) return;
        anchorActivators.put(block, player.getUniqueId());
        plugin.debug("Tracked anchor interaction: " + player.getName() + " at " + block.getLocation());
    }

    public void registerPotentialExplosion(Location location, Player player) {
        if (!isEnabled() || location == null || player == null) return;
        explosionCache.put(location, new ExplosionData(player.getUniqueId()));
        plugin.debug("Registered potential explosion at " + location + " by " + player.getName());
        
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            explosionCache.remove(location);
        }, 100L);
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
        explosionCache.clear();
        plugin.debug("RespawnAnchorListener cleanup complete");
    }
}