package net.opmasterleo.combat.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.manager.SuperVanishManager;
import net.opmasterleo.combat.util.SchedulerUtil;

public final class EntityDamageByEntityListener implements PacketListener, Listener {
    private static final long ATTACK_TIMEOUT = 5000;
    private static final long CLEANUP_DELAY = 100L;
    
    private final Map<UUID, Player> projectileOwners = new ConcurrentHashMap<>();
    private final Map<UUID, Long> attackTimestamps = new ConcurrentHashMap<>();
    private final Combat combatInstance = Combat.getInstance();
    private boolean initialized = false;

    public EntityDamageByEntityListener() {
    }
    
    public void initialize() {
        if (initialized) return;
        
        combatInstance.getServer().getPluginManager().registerEvents(this, combatInstance);
        if (combatInstance.isPacketEventsAvailable()) {
            combatInstance.safelyRegisterPacketListener(this);
        }
        initialized = true;
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
        Entity target = combatInstance.getEntityManager().getEntity(interactPacket.getEntityId());
        
        if (target instanceof Player victim) {
            if (isInvalidAttack(attacker, victim)) return;
            handlePlayerAttack(attacker, victim);
        }
    }

    private void handleUseItemPacket(PacketReceiveEvent event) {
        WrapperPlayClientUseItem useItemPacket = new WrapperPlayClientUseItem(event);
        if (useItemPacket.getHand() != com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND) return;
        
        Player shooter = (Player) event.getPlayer();
        UUID shooterId = shooter.getUniqueId();
        projectileOwners.put(shooterId, shooter);
        attackTimestamps.put(shooterId, System.currentTimeMillis());
        
        scheduleCleanup();
    }

    private void scheduleCleanup() {
        if (attackTimestamps.size() < 100) return;
        
        SchedulerUtil.runTaskLater(combatInstance, () -> {
            long now = System.currentTimeMillis();
            attackTimestamps.entrySet().removeIf(entry -> now - entry.getValue() > ATTACK_TIMEOUT);
            projectileOwners.keySet().retainAll(attackTimestamps.keySet());
        }, CLEANUP_DELAY);
    }

    private boolean isInvalidAttack(Player attacker, Player victim) {
        return attacker.getHealth() <= 0 || victim.getHealth() <= 0 || 
               attacker.isDead() || victim.isDead();
    }

    private void handlePlayerAttack(Player attacker, Player victim) {
        if (isCreativeOrSpectator(attacker)) return;
        if (isProtectedInteraction(attacker, victim)) return;
        if (isSelfAttack(attacker, victim)) return;
        
        combatInstance.directSetCombat(victim, attacker);
        combatInstance.directSetCombat(attacker, victim);
    }

    private boolean isCreativeOrSpectator(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || 
               player.getGameMode() == GameMode.SPECTATOR;
    }

    private boolean isProtectedInteraction(Player attacker, Player victim) {
        NewbieProtectionListener protectionListener = combatInstance.getNewbieProtectionListener();
        if (protectionListener != null) {
            boolean attackerProtected = protectionListener.isActuallyProtected(attacker);
            boolean victimProtected = protectionListener.isActuallyProtected(victim);
            if (attackerProtected != victimProtected) return true;
        }

        if (combatInstance.getWorldGuardUtil() != null && combatInstance.getWorldGuardUtil().isPvpDenied(victim.getLocation())) {
            return true;
        }

        SuperVanishManager vanish = combatInstance.getSuperVanishManager();
        return vanish != null && (vanish.isVanished(attacker) || vanish.isVanished(victim));
    }

    private boolean isSelfAttack(Player attacker, Player victim) {
        if (attacker.getUniqueId().equals(victim.getUniqueId())) {
            if (combatInstance.getConfig().getBoolean("self-combat", false)) {
                combatInstance.directSetCombat(victim, victim);
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
        combatInstance.directSetCombat(victim, attacker);
        combatInstance.directSetCombat(attacker, victim);
    }

    private Player findLatestAttacker() {
        if (projectileOwners.isEmpty()) return null;
        
        Player latestAttacker = null;
        long latestTimestamp = 0;
        
        for (Map.Entry<UUID, Player> entry : projectileOwners.entrySet()) {
            UUID playerId = entry.getKey();
            Long timestamp = attackTimestamps.get(playerId);
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
        if (!combatInstance.getConfig().getBoolean("link-end-crystals", true)) return;
        
        Player placer = combatInstance.getCrystalManager() != null ? 
            combatInstance.getCrystalManager().getPlacer(crystal) : null;

        if (placer != null) {
            if (isSelfAttack(victim, placer)) return;
            
            combatInstance.directSetCombat(victim, placer);
            combatInstance.directSetCombat(placer, victim);
            setKillerIfLethal(victim, damage, placer);
        }
    }
    
    public void handlePetDamage(Player victim, Tameable pet, double damage) {
        if (!combatInstance.getConfig().getBoolean("link-pets", true)) return;
        if (!(pet.getOwner() instanceof Player owner)) return;
        if (owner.getUniqueId().equals(victim.getUniqueId())) return;
        
        combatInstance.directSetCombat(victim, owner);
        combatInstance.directSetCombat(owner, victim);
        setKillerIfLethal(victim, damage, owner);
    }
    
    public void handleFishingRodDamage(Player victim, FishHook hook, double damage) {
        if (!combatInstance.getConfig().getBoolean("link-fishing-rod", true)) return;
        if (!(hook.getShooter() instanceof Player shooter)) return;
        if (shooter.getUniqueId().equals(victim.getUniqueId())) {
            if (combatInstance.getConfig().getBoolean("self-combat", false)) {
                combatInstance.directSetCombat(victim, victim);
            }
            return;
        }
        
        if (isCreativeOrSpectator(shooter) || isCreativeOrSpectator(victim)) return;
        if (isProtectedInteraction(shooter, victim)) return;
        
        combatInstance.directSetCombat(victim, shooter);
        combatInstance.directSetCombat(shooter, victim);
        setKillerIfLethal(victim, damage, shooter);
    }
    
    public void handleTNTDamage(Player victim, TNTPrimed tnt, double damage) {
        if (!combatInstance.getConfig().getBoolean("link-tnt", true)) return;
        if (!(tnt.getSource() instanceof Player source)) return;
        
        if (source.getUniqueId().equals(victim.getUniqueId())) {
            handleSelfTNTDamage(victim, damage);
        } else {
            handleOtherTNTDamage(victim, source, damage);
        }
    }

    private void handleSelfTNTDamage(Player victim, double damage) {
        if (combatInstance.getConfig().getBoolean("self-combat", false)) {
            combatInstance.directSetCombat(victim, victim);
            if (victim.getHealth() <= damage) {
                Player opponent = combatInstance.getCombatOpponent(victim);
                if (opponent != null && !opponent.equals(victim)) {
                    victim.setKiller(opponent);
                }
            }
        }
    }

    private void handleOtherTNTDamage(Player victim, Player source, double damage) {
        combatInstance.directSetCombat(victim, source);
        combatInstance.directSetCombat(source, victim);
        setKillerIfLethal(victim, damage, source);
    }
    
    public void handleRespawnAnchorDamage(Player victim, Entity explosion, double damage) {
        if (!combatInstance.getConfig().getBoolean("link-respawn-anchor", true)) return;
        if (!explosion.hasMetadata("respawn_anchor_activator")) return;
        
        Object activatorValue = explosion.getMetadata("respawn_anchor_activator").get(0).value();
        if (!(activatorValue instanceof Player activator)) return;
        
        if (activator.getUniqueId().equals(victim.getUniqueId())) {
            if (combatInstance.getConfig().getBoolean("self-combat", false)) {
                combatInstance.setCombat(victim, victim);
            }
        } else {
            combatInstance.setCombat(victim, activator);
            combatInstance.setCombat(activator, victim);
            setKillerIfLethal(victim, damage, activator);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (combatInstance.getRespawnAnchorListener() != null) {
            combatInstance.getRespawnAnchorListener().onEntityDamage(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        combatInstance.debug("EntityDamageByEntityEvent triggered: " + event.getEntity().getType() + 
                    " damaged by " + event.getDamager().getType() + 
                    " for " + event.getDamage() + " damage");
        
        if (event.getEntity() instanceof Player victim) {
            switch (event.getDamager()) {
                case TNTPrimed tnt -> handleTNTDamage(victim, tnt, event.getFinalDamage());
                case EnderCrystal crystal -> handleCrystalDamage(victim, crystal, event.getFinalDamage());
                case FishHook hook -> handleFishingRodDamage(victim, hook, event.getFinalDamage());
                case Tameable pet -> handlePetDamage(victim, pet, event.getFinalDamage());
                default -> { /* Do nothing */ }
            }
            
            if (combatInstance.getRespawnAnchorListener() != null && 
                (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || 
                 event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) {
                combatInstance.debug("Delegating explosion damage to RespawnAnchorListener");
                combatInstance.getRespawnAnchorListener().onEntityDamage(event);
            }
        }
    }
}