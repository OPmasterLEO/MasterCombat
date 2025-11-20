package net.opmasterleo.combat.listener;

import java.util.ArrayList;
import java.util.List;

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

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.manager.SuperVanishManager;
import net.opmasterleo.combat.util.SchedulerUtil;
import net.opmasterleo.combat.util.WorldGuardUtil;

public final class EntityDamageByEntityListener implements PacketListener, Listener {
    private static final long ATTACK_TIMEOUT = 5000;
    private static final long CLEANUP_DELAY = 60L;
    private static final int CLEANUP_THRESHOLD = 100;
    
    private static class AttackData {
        final Player player;
        long timestamp;
        
        AttackData(Player player) {
            this.player = player;
            this.timestamp = System.currentTimeMillis();
        }
        
        void updateTimestamp() {
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired(long now) {
            return now - timestamp > ATTACK_TIMEOUT;
        }
    }
    
    private final ConcurrentLong2ReferenceChainedHashTable<AttackData> attackData = ConcurrentLong2ReferenceChainedHashTable.createWithExpected(128, 0.75f);
    private final List<AttackData> attackDataList = new ArrayList<>(128);
    private final Combat combatInstance = Combat.getInstance();
    private boolean initialized = false;
    private NewbieProtectionListener protectionListener;
    private WorldGuardUtil worldGuardUtil;
    private SuperVanishManager vanishManager;

    public EntityDamageByEntityListener() {
    }
    
    public void initialize() {
        if (initialized) return;
        protectionListener = combatInstance.getNewbieProtectionListener();
        worldGuardUtil = combatInstance.getWorldGuardUtil();
        vanishManager = combatInstance.getSuperVanishManager();
        
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
        long shooterKey = Combat.uuidToLong(shooter.getUniqueId());
        AttackData existing = attackData.get(shooterKey);
        if (existing != null) {
            existing.updateTimestamp();
        } else {
            AttackData data = new AttackData(shooter);
            attackData.put(shooterKey, data);
            attackDataList.add(data);
        }
        if (attackData.size() >= CLEANUP_THRESHOLD) {
            scheduleCleanup();
        }
    }

    private void scheduleCleanup() {
        SchedulerUtil.runTaskLater(combatInstance, () -> {
            long now = System.currentTimeMillis();
            attackDataList.removeIf(data -> {
                boolean expired = data.isExpired(now) || data.player == null || !data.player.isOnline();
                if (expired) {
                    long key = Combat.uuidToLong(data.player.getUniqueId());
                    attackData.remove(key);
                }
                return expired;
            });
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
        if (protectionListener != null) {
            boolean attackerProtected = protectionListener.isActuallyProtected(attacker);
            boolean victimProtected = protectionListener.isActuallyProtected(victim);
            if (attackerProtected != victimProtected) return true;
        }

        if (worldGuardUtil != null && worldGuardUtil.isPvpDenied(victim.getLocation())) {
            return true;
        }

        return vanishManager != null && (vanishManager.isVanished(attacker) || vanishManager.isVanished(victim));
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
        if (attackDataList.isEmpty()) return null;
        AttackData latest = null;
        long latestTimestamp = 0L;
        for (int i = 0; i < attackDataList.size(); i++) {
            AttackData data = attackDataList.get(i);
            if (data == null) continue;
            if (data.timestamp > latestTimestamp) {
                latestTimestamp = data.timestamp;
                latest = data;
            }
        }
        return latest != null ? latest.player : null;
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