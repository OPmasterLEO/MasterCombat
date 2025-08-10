package net.opmasterleo.combat.listener;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.manager.SuperVanishManager;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.Listener;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityDamageByEntityListener implements PacketListener, Listener {
    private static final long ATTACK_TIMEOUT = 5000;
    private static final long CLEANUP_DELAY = 100L;
    
    private final Map<UUID, Player> projectileOwners = new ConcurrentHashMap<>();
    private final Map<UUID, Long> attackTimestamps = new ConcurrentHashMap<>();
    private final Combat combat = Combat.getInstance();

    public EntityDamageByEntityListener() {
        Combat combat = Combat.getInstance();
        combat.getServer().getPluginManager().registerEvents(this, combat);
        if (combat.isPacketEventsAvailable()) {
            combat.safelyRegisterPacketListener(this);
        }
    }
    
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            handleAttackPacket(event);
        } else if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            handleUseItemPacket(event);
        }
    }

    private void handleAttackPacket(PacketReceiveEvent event) {
        WrapperPlayClientInteractEntity interactPacket = new WrapperPlayClientInteractEntity(event);
        if (interactPacket.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
        
        Player attacker = (Player) event.getPlayer();
        Entity target = combat.getEntityManager().getEntity(interactPacket.getEntityId());
        
        if (target instanceof Player victim) {
            if (isInvalidAttack(attacker, victim)) return;
            handlePlayerAttack(attacker, victim);
        }
    }

    private void handleUseItemPacket(PacketReceiveEvent event) {
        WrapperPlayClientUseItem useItemPacket = new WrapperPlayClientUseItem(event);
        if (useItemPacket.getHand() != com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND) return;
        
        Player shooter = (Player) event.getPlayer();
        projectileOwners.put(shooter.getUniqueId(), shooter);
        attackTimestamps.put(shooter.getUniqueId(), System.currentTimeMillis());
        
        scheduleCleanup();
    }

    private void scheduleCleanup() {
        Bukkit.getScheduler().runTaskLater(combat, () -> {
            long now = System.currentTimeMillis();
            attackTimestamps.entrySet().removeIf(entry -> now - entry.getValue() > ATTACK_TIMEOUT);
            projectileOwners.keySet().retainAll(attackTimestamps.keySet());
        }, CLEANUP_DELAY);
    }

    private boolean isInvalidAttack(Player attacker, Player victim) {
        return attacker.isDead() || victim.isDead() || 
               attacker.getHealth() <= 0 || victim.getHealth() <= 0;
    }

    private void handlePlayerAttack(Player attacker, Player victim) {
        if (isCreativeOrSpectator(attacker)) return;
        if (isProtectedInteraction(attacker, victim)) return;
        if (isSelfAttack(attacker, victim)) return;
        
        combat.directSetCombat(victim, attacker);
        combat.directSetCombat(attacker, victim);
    }

    private boolean isCreativeOrSpectator(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || 
               player.getGameMode() == GameMode.SPECTATOR;
    }

    private boolean isProtectedInteraction(Player attacker, Player victim) {
        NewbieProtectionListener protectionListener = combat.getNewbieProtectionListener();
        if (protectionListener != null) {
            boolean attackerProtected = protectionListener.isActuallyProtected(attacker);
            boolean victimProtected = protectionListener.isActuallyProtected(victim);
            if (attackerProtected != victimProtected) return true;
        }

        if (combat.getWorldGuardUtil() != null && combat.getWorldGuardUtil().isPvpDenied(victim.getLocation())) {
            return true;
        }

        SuperVanishManager vanish = combat.getSuperVanishManager();
        return vanish != null && (vanish.isVanished(attacker) || vanish.isVanished(victim));
    }

    private boolean isSelfAttack(Player attacker, Player victim) {
        if (attacker.getUniqueId().equals(victim.getUniqueId())) {
            if (combat.getConfig().getBoolean("self-combat", false)) {
                combat.directSetCombat(victim, victim);
            }
            return true;
        }
        return false;
    }

    public void handleDamage(Player victim, double damage) {
        if (isCreativeOrSpectator(victim) || damage <= 0) return;
        
        Player attacker = findLatestAttacker();
        if (attacker == null) return;
        
        setKillerIfLethal(victim, damage, attacker);
        combat.directSetCombat(victim, attacker);
        combat.directSetCombat(attacker, victim);
    }

    private Player findLatestAttacker() {
        Player latestAttacker = null;
        long latestTimestamp = 0;
        
        for (Map.Entry<UUID, Player> entry : projectileOwners.entrySet()) {
            Long timestamp = attackTimestamps.get(entry.getKey());
            if (timestamp != null && timestamp > latestTimestamp) {
                latestTimestamp = timestamp;
                latestAttacker = entry.getValue();
            }
        }
        return latestAttacker;
    }

    private void setKillerIfLethal(Player victim, double damage, Player killer) {
        if (victim.getHealth() <= damage) {
            victim.setKiller(killer);
        }
    }

    public void handleCrystalDamage(Player victim, Entity crystal, double damage) {
        if (!combat.getConfig().getBoolean("link-end-crystals", true)) return;
        
        Player placer = combat.getCrystalManager() != null ? 
            combat.getCrystalManager().getPlacer(crystal) : null;

        if (placer != null) {
            if (isSelfAttack(victim, placer)) return;
            
            combat.directSetCombat(victim, placer);
            combat.directSetCombat(placer, victim);
            setKillerIfLethal(victim, damage, placer);
        }
    }
    
    public void handlePetDamage(Player victim, Tameable pet, double damage) {
        if (!combat.getConfig().getBoolean("link-pets", true)) return;
        if (!(pet.getOwner() instanceof Player owner)) return;
        if (owner.getUniqueId().equals(victim.getUniqueId())) return;
        
        combat.directSetCombat(victim, owner);
        combat.directSetCombat(owner, victim);
        setKillerIfLethal(victim, damage, owner);
    }
    
    public void handleFishingRodDamage(Player victim, FishHook hook, double damage) {
        if (!combat.getConfig().getBoolean("link-fishing-rod", true)) return;
        if (!(hook.getShooter() instanceof Player shooter)) return;
        if (shooter.getUniqueId().equals(victim.getUniqueId())) return;
        
        combat.directSetCombat(victim, shooter);
        combat.directSetCombat(shooter, victim);
        setKillerIfLethal(victim, damage, shooter);
    }
    
    public void handleTNTDamage(Player victim, TNTPrimed tnt, double damage) {
        if (!combat.getConfig().getBoolean("link-tnt", true)) return;
        if (!(tnt.getSource() instanceof Player source)) return;
        
        if (source.getUniqueId().equals(victim.getUniqueId())) {
            handleSelfTNTDamage(victim, damage);
        } else {
            handleOtherTNTDamage(victim, source, damage);
        }
    }

    private void handleSelfTNTDamage(Player victim, double damage) {
        if (combat.getConfig().getBoolean("self-combat", false)) {
            combat.directSetCombat(victim, victim);
            if (victim.getHealth() <= damage) {
                Player opponent = combat.getCombatOpponent(victim);
                if (opponent != null && !opponent.equals(victim)) {
                    victim.setKiller(opponent);
                }
            }
        }
    }

    private void handleOtherTNTDamage(Player victim, Player source, double damage) {
        combat.directSetCombat(victim, source);
        combat.directSetCombat(source, victim);
        setKillerIfLethal(victim, damage, source);
    }
    
    public void handleRespawnAnchorDamage(Player victim, Entity explosion, double damage) {
        if (!combat.getConfig().getBoolean("link-respawn-anchor", true)) return;
        if (!explosion.hasMetadata("respawn_anchor_activator")) return;
        
        Object activatorValue = explosion.getMetadata("respawn_anchor_activator").get(0).value();
        if (!(activatorValue instanceof Player activator)) return;
        
        if (activator.getUniqueId().equals(victim.getUniqueId())) {
            if (combat.getConfig().getBoolean("self-combat", false)) {
                combat.setCombat(victim, victim);
            }
        } else {
            combat.setCombat(victim, activator);
            combat.setCombat(activator, victim);
            setKillerIfLethal(victim, damage, activator);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Combat combat = Combat.getInstance();
        if (combat.getRespawnAnchorListener() != null) {
            combat.getRespawnAnchorListener().onEntityDamage(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Combat combat = Combat.getInstance();
        combat.debug("EntityDamageByEntityEvent triggered: " + event.getEntity().getType() + 
                    " damaged by " + event.getDamager().getType() + 
                    " for " + event.getDamage() + " damage");
        
        if (event.getEntity() instanceof Player victim) {
            if (event.getDamager() instanceof TNTPrimed tnt) {
                handleTNTDamage(victim, tnt, event.getFinalDamage());
            } else if (event.getDamager() instanceof EnderCrystal crystal) {
                handleCrystalDamage(victim, crystal, event.getFinalDamage());
            } else if (event.getDamager() instanceof FishHook hook) {
                handleFishingRodDamage(victim, hook, event.getFinalDamage());
            } else if (event.getDamager() instanceof Tameable pet) {
                handlePetDamage(victim, pet, event.getFinalDamage());
            }
            
            if (combat.getRespawnAnchorListener() != null && 
                (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || 
                 event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) {
                combat.debug("Delegating explosion damage to RespawnAnchorListener");
                combat.getRespawnAnchorListener().onEntityDamage(event);
            }
        }
    }
}