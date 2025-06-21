package net.opmasterleo.combat.listener;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.Component;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.manager.PlaceholderManager;

public class NewbieProtectionListener implements Listener {

    private final HashMap<UUID, ProtectionData> protectedPlayers = new HashMap<>();

    private static class ProtectionData {
        long remainingSeconds;
        long lastOnlineMillis;
        ProtectionData(long remainingSeconds, long lastOnlineMillis) {
            this.remainingSeconds = remainingSeconds;
            this.lastOnlineMillis = lastOnlineMillis;
        }
    }

    private String blockedMessageType;

    public NewbieProtectionListener() {
        // Do not call reloadConfigCache() here to avoid overridable method call in constructor
    }

    public void init() {
        reloadConfigCache();
    }

    public void reloadConfigCache() {
        Combat combat = Combat.getInstance();
        if (combat == null || combat.getConfig() == null) return;
        blockedMessageType = combat.getConfig().getString("NewbieProtection.blockedMessages.type", "chat").toLowerCase();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();
        if (combat == null || combat.getConfig() == null) return;
        if (!combat.getConfig().getBoolean("NewbieProtection.enabled", true)) return;

        if (!player.hasPlayedBefore()) {
            long protectionTime = combat.getConfig().getLong("NewbieProtection.time", 300);
            protectedPlayers.put(player.getUniqueId(), new ProtectionData(protectionTime, System.currentTimeMillis()));

            String message = PlaceholderManager.applyPlaceholders(player,
                    combat.getConfig().getString("NewbieProtection.protectedMessage"), protectionTime);
            player.sendMessage(message);
        } else if (protectedPlayers.containsKey(player.getUniqueId())) {
            // Resume timer
            protectedPlayers.get(player.getUniqueId()).lastOnlineMillis = System.currentTimeMillis();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ProtectionData data = protectedPlayers.get(player.getUniqueId());
        if (data != null) {
            long now = System.currentTimeMillis();
            long elapsed = (now - data.lastOnlineMillis) / 1000;
            if (elapsed > 0) {
                data.remainingSeconds = Math.max(0, data.remainingSeconds - elapsed);
            }
            data.lastOnlineMillis = now;
        }
    }

    private void sendBlockedMessage(Player player, String messageKey, long time) {
        Combat combat = Combat.getInstance();
        String type = blockedMessageType;
        // Use correct config path for messages
        String msg = PlaceholderManager.applyPlaceholders(player,
                combat.getConfig().getString("NewbieProtection.blockedMessages.messages." + messageKey), time);
        if (msg == null || msg.isEmpty()) return;
        try {
            switch (type) {
                case "actionbar":
                    player.sendActionBar(Component.text(msg));
                    break;
                case "title":
                    player.showTitle(net.kyori.adventure.title.Title.title(Component.empty(), Component.text(msg)));
                    break;
                case "subtitle":
                    player.showTitle(net.kyori.adventure.title.Title.title(Component.text(""), Component.text(msg)));
                    break;
                case "chat":
                default:
                    player.sendMessage(Component.text(msg));
                    break;
            }
        } catch (Throwable e) {
            player.sendMessage(Component.text(msg));
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity victimEntity = event.getEntity();
        Entity damagerEntity = event.getDamager();
        Player victim = (victimEntity instanceof Player) ? (Player) victimEntity : null;
        Player attacker = null;

        // Always check if the damager is a player (e.g., breaking a crystal)
        if (damagerEntity instanceof Player playerDamager) {
            attacker = playerDamager;
        } else if (damagerEntity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        } else if (damagerEntity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player tntSource) {
            attacker = tntSource;
        } else if (damagerEntity instanceof EnderCrystal && Combat.getInstance().getCrystalManager() != null) {
            Player placer = Combat.getInstance().getCrystalManager().getPlacer(damagerEntity);
            if (placer != null) {
                attacker = placer;
            } else if (event.getDamager() instanceof Player crystalBreaker) {
                // If no placer, but the crystal was broken by a player, treat them as attacker
                attacker = crystalBreaker;
            }
        }

        expireProtections();

        boolean mobsProtect = Combat.getInstance().getConfig().getBoolean("NewbieProtection.MobsProtect", false);

        if (victim == null && !mobsProtect) {
            return;
        }

        // Block protected player from damaging others with crystals (or any method)
        boolean attackerProtected = attacker != null && isProtected(attacker);
        boolean victimProtected = victim != null && isProtected(victim);

        if (attackerProtected) {
            // Tag both players if possible
            if (attacker != null && victim != null) {
                Combat.getInstance().setCombat(attacker, victim);
            }
            // Only notify the protected player, not the victim
            sendBlockedMessage(attacker, "TriedAttackMessage", 0);
            if (attacker != null) {
                attacker.playSound(attacker.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
            event.setCancelled(true);
            return;
        }
        if (victimProtected) {
            // Tag both players if possible
            if (attacker != null && victim != null) {
                Combat.getInstance().setCombat(attacker, victim);
            }
            // Only notify the protected victim and the attacker (if not protected)
            if (attacker != null) {
                sendBlockedMessage(attacker, "AttackerMessage", 0);
            }
            sendBlockedMessage(victim, "TriedAttackMessage", 0);
            event.setCancelled(true);
        }
    }

    private void expireProtections() {
        Combat combat = Combat.getInstance();
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, ProtectionData>> it = protectedPlayers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ProtectionData> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            ProtectionData data = entry.getValue();
            if (player != null && player.isOnline()) {
                long elapsed = (now - data.lastOnlineMillis) / 1000;
                if (elapsed > 0) {
                    data.remainingSeconds = Math.max(0, data.remainingSeconds - elapsed);
                    data.lastOnlineMillis = now;
                }
                if (data.remainingSeconds <= 0) {
                    it.remove();
                    String msg = combat.getConfig().getString("NewbieProtection.DisabledMessage");
                    if (msg != null && !msg.isEmpty()) {
                        player.sendMessage(PlaceholderManager.applyPlaceholders(player, msg, 0));
                    }
                }
            }
        }
    }

    public void removeProtection(Player player) {
        protectedPlayers.remove(player.getUniqueId());
    }

    public boolean isProtected(Player player) {
        ProtectionData data = protectedPlayers.get(player.getUniqueId());
        if (data == null) return false;
        if (data.remainingSeconds <= 0) {
            protectedPlayers.remove(player.getUniqueId());
            String msg = Combat.getInstance().getConfig().getString("NewbieProtection.DisabledMessage");
            if (msg != null && !msg.isEmpty()) {
                player.sendMessage(PlaceholderManager.applyPlaceholders(player, msg, 0));
            }
            return false;
        }
        return true;
    }
}
