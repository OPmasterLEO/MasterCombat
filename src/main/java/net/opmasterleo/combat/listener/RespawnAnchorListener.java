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
    private final Combat plugin = Combat.getInstance();
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
        
        Combat combat = Combat.getInstance();
        NewbieProtectionListener protectionListener = combat.getNewbieProtectionListener();
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
                        return;
                    }
                }
            }
        }

        if (isAnchor) {
            anchorActivators.put(block, player.getUniqueId());
            block.setMetadata("anchor_activator_uuid", 
                new FixedMetadataValue(plugin, player.getUniqueId()));

            BlockData data = block.getBlockData();
            if (data instanceof RespawnAnchor anchor && 
                anchor.getCharges() > 0 && 
                !player.getInventory().getItemInMainHand().getType().equals(Material.GLOWSTONE)) {
                if (plugin.getConfig().getBoolean("self-combat", false)) {
                    plugin.directSetCombat(player, player);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!isEnabled()) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        
        NewbieProtectionListener protectionListener = Combat.getInstance().getNewbieProtectionListener();
        if (protectionListener != null && protectionListener.isActuallyProtected(victim)) {
            if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
                event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                event.setCancelled(true);
                return;
            }
        }

        if (shouldBypass(victim)) return;
        if (event.getCause() != DamageCause.BLOCK_EXPLOSION && 
            event.getCause() != DamageCause.ENTITY_EXPLOSION) {
            return;
        }

        if (event.getFinalDamage() <= 0) return;

        Location damageLocation = victim.getLocation();
        Player activator = findActivatorForDamage(damageLocation);
        if (activator != null && !shouldBypass(activator)) {
            boolean selfCombat = plugin.getConfig().getBoolean("self-combat", false);
            
            if (activator.getUniqueId().equals(victim.getUniqueId())) {
                if (selfCombat) {
                    plugin.directSetCombat(activator, activator);
                }
            } else {
                plugin.directSetCombat(activator, victim);
                plugin.directSetCombat(victim, activator);
                
                // Set the killer for attribution if this is fatal damage
                if (victim.getHealth() <= event.getFinalDamage()) {
                    victim.setKiller(activator);
                }
                
                // Set the killer for attribution if this is fatal damage
                if (victim.getHealth() <= event.getFinalDamage()) {
                    victim.setKiller(activator);
                }
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
                    Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                        explosionCache.remove(event.getLocation());
                    }, 100L);
                    
                    break;
                }
            }
        }
        if (explosionCache.size() > 100) {
            long now = System.currentTimeMillis();
            explosionCache.entrySet().removeIf(entry -> 
                now - entry.getValue().timestamp > 10000);
        }
    }
    public void trackAnchorInteraction(Block block, Player player) {
        if (!isEnabled() || block == null || block.getType() != Material.RESPAWN_ANCHOR || player == null) return;
        anchorActivators.put(block, player.getUniqueId());
    }

    public void registerPotentialExplosion(Location location, Player player) {
        if (!isEnabled() || location == null || player == null) return;
        explosionCache.put(location, new ExplosionData(player.getUniqueId()));
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            explosionCache.remove(location);
        }, 100L);
        if (plugin.getConfig().getBoolean("self-combat", false)) {
            plugin.directSetCombat(player, player);
        }
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
    }
}