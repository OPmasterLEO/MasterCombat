package net.opmasterleo.combat.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.manager.PlaceholderManager;
import net.opmasterleo.combat.util.ChatUtil;
import net.opmasterleo.combat.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class NewbieProtectionListener implements Listener {

    private final Map<UUID, Long> protectedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> anchorActivators = new ConcurrentHashMap<>();
    private final Map<UUID, Long> offlineProtection = new ConcurrentHashMap<>();
    private long protectionDuration;
    private boolean enabled;

    private boolean mobsProtect;
    private String messageType;
    private String disableCommand;

    private String msgProtected;
    private String msgDisabled;
    private String msgTriedAttack;
    private String msgAttacker;
    private String msgExpired;
    private String crystalBlockMessage;
    private String anchorBlockMessage;
    private String protectionLeftMessage;
    private String blockedItemMessage;

    private int titleFadeIn = 10;
    private int titleStay = 70;
    private int titleFadeOut = 20;
    private Set<Material> blockedItems;

    public NewbieProtectionListener() {
        reloadConfig();
        startExpirationChecker();
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void startExpirationChecker() {
        if (!enabled) return;

        SchedulerUtil.runTaskTimer(Combat.getInstance(), () -> {
            long currentTime = System.currentTimeMillis();
            List<UUID> toRemove = new ArrayList<>();

            for (Map.Entry<UUID, Long> entry : protectedPlayers.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null) {
                    offlineProtection.put(entry.getKey(), entry.getValue());
                    toRemove.add(entry.getKey());
                    continue;
                }

                if (currentTime >= entry.getValue()) {
                    toRemove.add(entry.getKey());
                    sendProtectionExpired(player);
                } else {
                    if (protectionLeftMessage != null && !protectionLeftMessage.isEmpty()) {
                        long remaining = entry.getValue() - currentTime;
                        if (remaining > 0 && remaining / 1000 % 5 == 0) {
                            String message = PlaceholderManager.applyPlaceholders(player, protectionLeftMessage, remaining / 1000);
                            sendMessage(player, message);
                        }
                    }
                }
            }

            for (UUID playerId : toRemove) {
                protectedPlayers.remove(playerId);
            }
        }, 20L, 20L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!player.hasPlayedBefore()) {
            addProtectedPlayer(player);
        } else if (offlineProtection.containsKey(playerId)) {
            long remainingTime = offlineProtection.remove(playerId) - System.currentTimeMillis();
            if (remainingTime > 0) {
                protectedPlayers.put(playerId, System.currentTimeMillis() + remainingTime);
                sendProtectionMessage(player);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
    }

    public void addProtectedPlayer(Player player) {
        if (!enabled) return;

        protectedPlayers.put(player.getUniqueId(), System.currentTimeMillis() + protectionDuration);
        sendProtectionMessage(player);
    }

    public void removeProtection(Player player) {
        protectedPlayers.remove(player.getUniqueId());
        offlineProtection.remove(player.getUniqueId());
        sendProtectionRemoved(player);
    }

    public boolean isActuallyProtected(Player player) {
        if (!enabled) return false;

        Long until = protectedPlayers.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public void sendProtectionMessage(Player player) {
        long remaining = protectedPlayers.get(player.getUniqueId()) - System.currentTimeMillis();
        String message = PlaceholderManager.applyPlaceholders(player, msgProtected, remaining / 1000);
        sendMessage(player, message);
    }

    public void sendProtectionRemoved(Player player) {
        sendMessage(player, msgDisabled);
    }

    public void sendProtectionExpired(Player player) {
        if (msgExpired != null && !msgExpired.isEmpty()) {
            sendMessage(player, msgExpired);
        }
    }

    public void sendBlockedMessage(Player player, String message) {
        if (message != null && !message.isEmpty()) {
            sendMessage(player, message);
        }
    }

    private void sendMessage(Player player, String message) {
        if (message == null || message.isEmpty()) return;
        Component formatted = ChatUtil.parse(message);

        switch (messageType.toLowerCase()) {
            case "actionbar":
                player.sendActionBar(formatted);
                break;
            case "title":
                player.showTitle(Title.title(
                    formatted,
                    Component.empty(),
                    Title.Times.times(
                        Duration.ofMillis(titleFadeIn * 50L),
                        Duration.ofMillis(titleStay * 50L),
                        Duration.ofMillis(titleFadeOut * 50L)
                    )
                ));
                break;
            case "subtitle":
                player.showTitle(Title.title(
                    Component.empty(),
                    formatted,
                    Title.Times.times(
                        Duration.ofMillis(titleFadeIn * 50L),
                        Duration.ofMillis(titleStay * 50L),
                        Duration.ofMillis(titleFadeOut * 50L)
                    )
                ));
                break;
            case "both":
                player.showTitle(Title.title(
                    formatted,
                    formatted,
                    Title.Times.times(
                        Duration.ofMillis(titleFadeIn * 50L),
                        Duration.ofMillis(titleStay * 50L),
                        Duration.ofMillis(titleFadeOut * 50L)
                    )
                ));
                break;
            case "none":
                break;
            default:
                player.sendMessage(formatted);
                break;
        }
    }

    public String getCrystalBlockMessage() {
        return crystalBlockMessage;
    }

    public String getAnchorBlockMessage() {
        return anchorBlockMessage;
    }

    public void trackAnchorActivator(UUID anchorId, Player player) {
        anchorActivators.put(anchorId, player.getUniqueId());
    }

    public Player getAnchorActivator(UUID anchorId) {
        UUID playerId = anchorActivators.get(anchorId);
        if (playerId == null) return null;
        return Combat.getInstance().getServer().getPlayer(playerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!enabled) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        if (mobsProtect && isActuallyProtected(victim)) {
            if (event instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent damageEvent = (EntityDamageByEntityEvent) event;
                Entity damager = damageEvent.getDamager();

                if (damager instanceof Monster || damager instanceof Animals) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (!(event instanceof EntityDamageByEntityEvent)) return;

        EntityDamageByEntityEvent damageEvent = (EntityDamageByEntityEvent) event;
        Player attacker = null;

        if (damageEvent.getDamager() instanceof Player) {
            attacker = (Player) damageEvent.getDamager();
        } else if (damageEvent.getDamager() instanceof EnderCrystal) {
            attacker = Combat.getInstance().getCrystalManager().getPlacer(damageEvent.getDamager());
        }

        if (attacker == null) return;

        if (isActuallyProtected(attacker) && !isActuallyProtected(victim)) {
            event.setCancelled(true);
            String message = (damageEvent.getDamager() instanceof EnderCrystal) ?
                crystalBlockMessage :
                msgTriedAttack.replace("%command%", disableCommand);
            sendBlockedMessage(attacker, message);
        }
        else if (!isActuallyProtected(attacker) && isActuallyProtected(victim)) {
            event.setCancelled(true);
            sendBlockedMessage(attacker, msgAttacker);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        if (!isActuallyProtected(player)) return;

        Material material = event.getBlock().getType();

        if (blockedItems.contains(material)) {
            event.setCancelled(true);
            sendBlockedMessage(player, blockedItemMessage);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (!isActuallyProtected(player)) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        if (blockedItems.contains(item.getType())) {
            event.setCancelled(true);
            sendBlockedMessage(player, blockedItemMessage);
        }
    }

    public void reloadConfig() {
        Combat combat = Combat.getInstance();
        enabled = combat.getConfig().getBoolean("NewbieProtection.enabled", true);
        protectionDuration = TimeUnit.SECONDS.toMillis(
            combat.getConfig().getLong("NewbieProtection.time", 300)
        );

        mobsProtect = combat.getConfig().getBoolean("NewbieProtection.settings.MobsProtect", false);
        messageType = combat.getConfig().getString("NewbieProtection.settings.MessageType", "actionbar");
        disableCommand = combat.getConfig().getString("NewbieProtection.settings.disableCommand", "removeprotect");

        msgProtected = combat.getConfig().getString("NewbieProtection.Messages.protectedMessage",
            "&aYou are protected from PvP for %time% seconds.");
        msgDisabled = combat.getConfig().getString("NewbieProtection.Messages.DisabledMessage",
            "&cYou are no longer protected from PvP.");
        msgTriedAttack = combat.getConfig().getString("NewbieProtection.Messages.TriedAttackMessage",
            "&cYou cannot attack while protected. Use /%command% to disable.");
        msgAttacker = combat.getConfig().getString("NewbieProtection.Messages.AttackerMessage",
            "&cYou cannot attack that user while in protected mode.");
        msgExpired = combat.getConfig().getString("NewbieProtection.Messages.ExpiredMessage",
            "&cYour newbie protection has expired!");
        crystalBlockMessage = combat.getConfig().getString("NewbieProtection.Messages.CrystalBlockMessage",
            "&cYou cannot attack unprotected players with crystals while protected.");
        anchorBlockMessage = combat.getConfig().getString("NewbieProtection.Messages.AnchorBlockMessage",
            "&cYou cannot attack unprotected players with anchors while protected.");
        protectionLeftMessage = combat.getConfig().getString("NewbieProtection.Messages.ProtectionLeftMessage",
            "&aProtection Left: %time%");
        blockedItemMessage = combat.getConfig().getString("NewbieProtection.Messages.BlockedMessage",
            "&cYou can't use this item while protected.");

        titleFadeIn = combat.getConfig().getInt("NewbieProtection.settings.TitleFadeIn", 10);
        titleStay = combat.getConfig().getInt("NewbieProtection.settings.TitleStay", 70);
        titleFadeOut = combat.getConfig().getInt("NewbieProtection.settings.TitleFadeOut", 20);

        blockedItems = new HashSet<>();
        for (String itemName : combat.getConfig().getStringList("NewbieProtection.settings.BlockedItems")) {
            try {
                Material material = Material.valueOf(itemName.toUpperCase());
                blockedItems.add(material);
            } catch (IllegalArgumentException e) {
                combat.getLogger().warning("Invalid material in BlockedItems: " + itemName);
            }
        }
    }

    public boolean isMobsProtect() {
        return mobsProtect;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getDisableCommand() {
        return disableCommand;
    }

    public void handleAnchorInteract(Player player, Object hand) {
        if (!enabled) return;

        if (isActuallyProtected(player)) {
            Block targetBlock = player.getTargetBlock(null, 5);
            if (targetBlock != null && targetBlock.getType() == Material.RESPAWN_ANCHOR) {
                UUID anchorId = UUID.nameUUIDFromBytes(targetBlock.getLocation().toString().getBytes());
                trackAnchorActivator(anchorId, player);
            }
        }
    }

    public boolean shouldBlockCrystalUse(Player player, Entity crystal) {
        if (!enabled || !isActuallyProtected(player)) return false;

        for (Entity nearby : crystal.getNearbyEntities(6.0, 6.0, 6.0)) {
            if (nearby instanceof Player target &&
                !player.getUniqueId().equals(target.getUniqueId()) &&
                !isActuallyProtected(target)) {
                return true;
            }
        }

        return false;
    }

    public String getAttackerMessage() {
        return msgAttacker;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIntentionalGameDesignDamage(EntityDamageEvent event) {
        if (!enabled) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (isActuallyProtected(victim)) {
            if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
                event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                event.setCancelled(true);
                return;
            }
        }
    }
}