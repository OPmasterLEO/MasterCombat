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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityDamageByEntityListener implements PacketListener {

    private final Map<UUID, Player> projectileOwners = new ConcurrentHashMap<>();
    private final Map<UUID, Long> attackTimestamps = new ConcurrentHashMap<>();
    private static final long ATTACK_TIMEOUT = 5000;

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Combat combat = Combat.getInstance();
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interactPacket = new WrapperPlayClientInteractEntity(event);
            if (interactPacket.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
            
            Player attacker = (Player) event.getPlayer();
            Entity target = combat.getEntityManager().getEntity(interactPacket.getEntityId());
            
            if (target instanceof Player victim) {
                handlePlayerAttack(combat, attacker, victim);
            }
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            WrapperPlayClientUseItem useItemPacket = new WrapperPlayClientUseItem(event);
            if (useItemPacket.getHand() != com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND) return;
            Player shooter = (Player) event.getPlayer();
            projectileOwners.put(shooter.getUniqueId(), shooter);
            attackTimestamps.put(shooter.getUniqueId(), System.currentTimeMillis());
            Bukkit.getScheduler().runTaskLater(combat, () -> {
                long now = System.currentTimeMillis();
                attackTimestamps.entrySet().removeIf(entry -> now - entry.getValue() > ATTACK_TIMEOUT);
                projectileOwners.keySet().retainAll(attackTimestamps.keySet());
            }, 100L);
        }
    }

    private void handlePlayerAttack(Combat combat, Player attacker, Player victim) {
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        NewbieProtectionListener protectionListener = combat.getNewbieProtectionListener();
        if (protectionListener != null) {
            boolean attackerProtected = protectionListener.isActuallyProtected(attacker);
            boolean victimProtected = protectionListener.isActuallyProtected(victim);
            if ((attackerProtected && !victimProtected) || (!attackerProtected && victimProtected)) {
                return;
            }
        }

        if (combat.getWorldGuardUtil() != null && combat.getWorldGuardUtil().isPvpDenied(victim)) {
            return;
        }

        SuperVanishManager vanish = combat.getSuperVanishManager();
        if (vanish != null && (vanish.isVanished(attacker) || vanish.isVanished(victim))) {
            return;
        }

        if (attacker.getUniqueId().equals(victim.getUniqueId())) {
            if (combat.getConfig().getBoolean("self-combat", false)) {
                combat.directSetCombat(victim, victim);
            }
            return;
        }

        combat.directSetCombat(victim, attacker);
        combat.directSetCombat(attacker, victim);
    }

    public void handleDamage(Player victim, org.bukkit.event.entity.EntityDamageEvent.DamageCause cause, double damage) {
        Combat combat = Combat.getInstance();
        if (victim.getGameMode() == GameMode.CREATIVE || victim.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (damage <= 0) return;
        Player attacker = null;
        long latestAttack = 0;
        for (Map.Entry<UUID, Player> entry : projectileOwners.entrySet()) {
            Long timestamp = attackTimestamps.get(entry.getKey());
            if (timestamp != null && timestamp > latestAttack) {
                latestAttack = timestamp;
                attacker = entry.getValue();
            }
        }

        if (attacker == null) return;
        if (victim.getHealth() <= damage) {
            victim.setKiller(attacker);
        }
        
        combat.directSetCombat(victim, attacker);
        combat.directSetCombat(attacker, victim);
    }

    public void handleCrystalDamage(Player victim, Entity crystal, double damage) {
        Combat combat = Combat.getInstance();
        if (!combat.getConfig().getBoolean("link-end-crystals", true)) return;
        Player placer = combat.getCrystalManager() != null ? 
            combat.getCrystalManager().getPlacer(crystal) : null;

        if (placer != null) {
            if (placer.getUniqueId().equals(victim.getUniqueId()) && 
                !combat.getConfig().getBoolean("self-combat", false)) {
                return;
            }
            
            combat.directSetCombat(victim, placer);
            combat.directSetCombat(placer, victim);
            
            if (victim.getHealth() <= damage) {
                victim.setKiller(placer);
            }
        }
    }
    
    public void handlePetDamage(Player victim, Tameable pet, double damage) {
        Combat combat = Combat.getInstance();
        if (!combat.getConfig().getBoolean("link-pets", true)) return;
        
        if (pet.getOwner() instanceof Player owner) {
            if (owner.getUniqueId().equals(victim.getUniqueId())) return;
            
            combat.directSetCombat(victim, owner);
            combat.directSetCombat(owner, victim);
            
            if (victim.getHealth() <= damage) {
                victim.setKiller(owner);
            }
        }
    }
    
    public void handleFishingRodDamage(Player victim, FishHook hook, double damage) {
        Combat combat = Combat.getInstance();
        if (!combat.getConfig().getBoolean("link-fishing-rod", true)) return;
        
        if (hook.getShooter() instanceof Player shooter) {
            if (shooter.getUniqueId().equals(victim.getUniqueId())) return;
            
            combat.directSetCombat(victim, shooter);
            combat.directSetCombat(shooter, victim);
            
            if (victim.getHealth() <= damage) {
                victim.setKiller(shooter);
            }
        }
    }
    
    public void handleTNTDamage(Player victim, TNTPrimed tnt, double damage) {
        Combat combat = Combat.getInstance();
        if (!combat.getConfig().getBoolean("link-tnt", true)) return;
        
        if (tnt.getSource() instanceof Player source) {
            if (source.getUniqueId().equals(victim.getUniqueId())) {
                if (combat.getConfig().getBoolean("self-combat", false)) {
                    combat.directSetCombat(victim, victim);
                    if (victim.getHealth() <= damage) {
                        Player opponent = combat.getCombatOpponent(victim);
                        if (opponent != null && !opponent.equals(victim)) {
                            victim.setKiller(opponent);
                        }
                    }
                }
            } else {
                combat.directSetCombat(victim, source);
                combat.directSetCombat(source, victim);
                if (victim.getHealth() <= damage) {
                    victim.setKiller(source);
                }
            }
        }
    }
    
    public void handleRespawnAnchorDamage(Player victim, Entity explosion, double damage) {
        Combat combat = Combat.getInstance();
        if (!combat.getConfig().getBoolean("link-respawn-anchor", true)) return;
        
        if (explosion.hasMetadata("respawn_anchor_explosion")) {
            Player activator = (Player) explosion.getMetadata("respawn_anchor_activator").get(0).value();
            if (activator != null) {
                if (activator.getUniqueId().equals(victim.getUniqueId())) {
                    if (combat.getConfig().getBoolean("self-combat", false)) {
                        combat.setCombat(victim, victim);
                    }
                } else {
                    combat.setCombat(victim, activator);
                    combat.setCombat(activator, victim);
                    if (victim.getHealth() <= damage) {
                        victim.setKiller(activator);
                    }
                }
            }
        }
    }
}