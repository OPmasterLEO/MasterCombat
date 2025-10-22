package net.opmasterleo.combat.listener;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;
import net.opmasterleo.combat.util.TimeUtil;

public class ItemRestrictionListener extends PacketListenerAbstract implements Listener {

    private static volatile boolean packetEventsEnabled = true;
    public static void disablePacketEventsIntegration() { packetEventsEnabled = false; }

    private final Combat plugin;
    private final Set<Material> disabledItems = ConcurrentHashMap.newKeySet();
    private boolean itemRestrictionsEnabled;
    private boolean enderpearlCooldownEnabled;
    private boolean enderpearlCombatOnly;
    private boolean tridentCooldownEnabled;
    private boolean tridentCombatOnly;
    private final Map<UUID, Long> enderpearlCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> tridentCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Boolean> worldTridentBans = new ConcurrentHashMap<>();
    private final Map<String, Boolean> worldEnderpearlCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Boolean> worldTridentCooldowns = new ConcurrentHashMap<>();
    private long enderpearlCooldownDuration;
    private long tridentCooldownDuration;
    private String enderpearlCooldownMessage;
    private String tridentCooldownMessage;
    private String itemRestrictedMessage;
    private boolean enderpearlBlockInCombat;
    private boolean tridentBlockInCombat;
    private String enderpearlCombatMessage;
    private String tridentCombatMessage;

    public ItemRestrictionListener() {
        this.plugin = Combat.getInstance();
    }

    public void initialize(Combat plugin) {
        if (plugin != null) {
        }
        reloadConfig();
        try {
            if (this.plugin != null && this.plugin.isPacketEventsAvailable()) {
                PacketEvents.getAPI().getEventManager().registerListener(this);
            }
        } catch (Exception ignored) {
        }
    }

    public void reloadConfig() {
        itemRestrictionsEnabled = plugin.getConfig().getBoolean("item_restrictions.enabled", false);
        disabledItems.clear();
        for (String item : plugin.getConfig().getStringList("item_restrictions.disabled_items")) {
            try {
                Material material = Material.valueOf(item.toUpperCase());
                disabledItems.add(material);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(String.format("Invalid material in disabled_items: %s", item));
            }
        }

        enderpearlCooldownEnabled = plugin.getConfig().getBoolean("enderpearl_cooldown.enabled", true);
        enderpearlCombatOnly = plugin.getConfig().getBoolean("enderpearl_cooldown.in_combat_only", true);
        String cooldownString = plugin.getConfig().getString("enderpearl_cooldown.duration", "10s");
        enderpearlCooldownDuration = TimeUtil.parseTimeToMillis(cooldownString);

        tridentCooldownEnabled = plugin.getConfig().getBoolean("trident_cooldown.enabled", true);
        tridentCombatOnly = plugin.getConfig().getBoolean("trident_cooldown.in_combat_only", true);
        String tridentCooldownString = plugin.getConfig().getString("trident_cooldown.duration", "10s");
        tridentCooldownDuration = TimeUtil.parseTimeToMillis(tridentCooldownString);
        
        enderpearlBlockInCombat = plugin.getConfig().getBoolean("enderpearl.block_in_combat", false);
        tridentBlockInCombat = plugin.getConfig().getBoolean("trident.block_in_combat", false);
        
        enderpearlCooldownMessage = plugin.getConfig().getString("Messages.Prefix", "") +
                "&cYou cannot use Ender Pearls for another &e%time%&c!";
        tridentCooldownMessage = plugin.getConfig().getString("Messages.Prefix", "") +
                "&cYou cannot use Tridents for another &e%time%&c!";
        enderpearlCombatMessage = plugin.getConfig().getString("enderpearl.combat_message",
                plugin.getConfig().getString("Messages.Prefix", "") + "&cYou cannot use ender pearls while in combat!");
        tridentCombatMessage = plugin.getConfig().getString("trident.combat_message",
                plugin.getConfig().getString("Messages.Prefix", "") + "&cYou cannot use tridents while in combat!");
        itemRestrictedMessage = plugin.getConfig().getString("Messages.Prefix", "") +
                "&cYou cannot use this item while in combat!";

        worldTridentBans.clear();
        ConfigurationSection tridentBanned = plugin.getConfig().getConfigurationSection("trident.banned_worlds");
        if (tridentBanned != null) {
            for (String world : tridentBanned.getKeys(false)) {
                worldTridentBans.put(world, plugin.getConfig().getBoolean("trident.banned_worlds." + world));
            }
        }

        worldEnderpearlCooldowns.clear();
        ConfigurationSection enderWorlds = plugin.getConfig().getConfigurationSection("enderpearl_cooldown.worlds");
        if (enderWorlds != null) {
            for (String world : enderWorlds.getKeys(false)) {
                worldEnderpearlCooldowns.put(world, plugin.getConfig().getBoolean("enderpearl_cooldown.worlds." + world));
            }
        }

        worldTridentCooldowns.clear();
        ConfigurationSection tridentCooldownWorlds = plugin.getConfig().getConfigurationSection("trident_cooldown.worlds");
        if (tridentCooldownWorlds != null) {
            for (String world : tridentCooldownWorlds.getKeys(false)) {
                worldTridentCooldowns.put(world, plugin.getConfig().getBoolean("trident_cooldown.worlds." + world));
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!packetEventsEnabled) return;
        if (!itemRestrictionsEnabled && !enderpearlCooldownEnabled && !tridentCooldownEnabled) return;

        Player player = (Player) event.getPlayer();
        if (!player.isOnline()) return;
        if (player.hasPermission("combat.bypass.safezone")) return;
        if (!itemRestrictionsEnabled && !plugin.isInCombat(player)) return;
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            handleUseItem(event, player);
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            handlePlayerDigging(event, player);
        }
    }

    private void handleUseItem(PacketReceiveEvent event, Player player) {
        if (!itemRestrictionsEnabled) return;
        WrapperPlayClientUseItem wrapper = new WrapperPlayClientUseItem(event);
        EquipmentSlot slot = wrapper.getHand() == InteractionHand.MAIN_HAND ?
                EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        ItemStack item = player.getInventory().getItem(slot);
        if (disabledItems.contains(item.getType())) {
            event.setCancelled(true);
            player.sendActionBar(ChatUtil.parse(itemRestrictedMessage));
            return;
        }

        if (item.getType() == Material.ENDER_PEARL) {
            if (isEnderpearlOnCooldown(player)) {
                event.setCancelled(true);
                long timeLeft = enderpearlCooldowns.getOrDefault(player.getUniqueId(), System.currentTimeMillis()) - System.currentTimeMillis();
                String formattedTime = TimeUtil.formatTime(timeLeft);
                player.sendActionBar(ChatUtil.parse(enderpearlCooldownMessage.replace("%time%", formattedTime)));
            } else if (shouldApplyEnderpearlCooldown(player)) {
                enderpearlCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + enderpearlCooldownDuration);
            }
        } else if (item.getType() == Material.TRIDENT) {
            if (worldTridentBans.getOrDefault(player.getWorld().getName(), false)) {
                event.setCancelled(true);
                player.sendActionBar(ChatUtil.parse("&cTridents are banned in this world!"));
                return;
            }

            if (isTridentOnCooldown(player)) {
                event.setCancelled(true);
                long timeLeft = tridentCooldowns.getOrDefault(player.getUniqueId(), System.currentTimeMillis()) - System.currentTimeMillis();
                String formattedTime = TimeUtil.formatTime(timeLeft);
                player.sendActionBar(ChatUtil.parse(tridentCooldownMessage.replace("%time%", formattedTime)));
            } else if (shouldApplyTridentCooldown(player)) {
                tridentCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + tridentCooldownDuration);
            }
        }
    }

    private void handlePlayerDigging(PacketReceiveEvent event, Player player) {
        WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
        if (wrapper.getAction() != DiggingAction.RELEASE_USE_ITEM) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.TRIDENT) return;
        if (worldTridentBans.getOrDefault(player.getWorld().getName(), false)) {
            event.setCancelled(true);
            player.sendActionBar(ChatUtil.parse("&cTridents are banned in this world!"));
            return;
        }

        if (isTridentOnCooldown(player)) {
            event.setCancelled(true);
            long timeLeft = tridentCooldowns.getOrDefault(player.getUniqueId(), System.currentTimeMillis()) - System.currentTimeMillis();
            String formattedTime = TimeUtil.formatTime(timeLeft);
            player.sendActionBar(ChatUtil.parse(tridentCooldownMessage.replace("%time%", formattedTime)));
        } else if (shouldApplyTridentCooldown(player)) {
            tridentCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + tridentCooldownDuration);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        ProjectileSource shooter = event.getEntity().getShooter();
        if (!(shooter instanceof Player)) {
            return;
        }

        Player player = (Player) shooter;
        if (event.getEntity() instanceof org.bukkit.entity.Trident) {
            if (worldTridentBans.getOrDefault(player.getWorld().getName(), false)) {
                event.setCancelled(true);
                player.sendActionBar(ChatUtil.parse("&cTridents are banned in this world!"));
                return;
            }

            if (isTridentOnCooldown(player)) {
                event.setCancelled(true);
                long timeLeft = tridentCooldowns.getOrDefault(player.getUniqueId(), System.currentTimeMillis()) - System.currentTimeMillis();
                String formattedTime = TimeUtil.formatTime(timeLeft);
                player.sendActionBar(ChatUtil.parse(tridentCooldownMessage.replace("%time%", formattedTime)));
            } else if (shouldApplyTridentCooldown(player)) {
                tridentCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + tridentCooldownDuration);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == TeleportCause.ENDER_PEARL) {
            Player player = event.getPlayer();
            if (plugin.isInCombat(player)) {
                plugin.keepPlayerInCombat(player);
            }
        }
    }

    private boolean isEnderpearlOnCooldown(Player player) {
        UUID playerId = player.getUniqueId();
        Long cooldownEnd = enderpearlCooldowns.get(playerId);
        if (cooldownEnd == null) return false;
        if (System.currentTimeMillis() >= cooldownEnd) {
            enderpearlCooldowns.remove(playerId);
            return false;
        }
        return true;
    }

    private boolean isTridentOnCooldown(Player player) {
        UUID playerId = player.getUniqueId();
        Long cooldownEnd = tridentCooldowns.get(playerId);
        if (cooldownEnd == null) return false;
        if (System.currentTimeMillis() >= cooldownEnd) {
            tridentCooldowns.remove(playerId);
            return false;
        }
        return true;
    }

    private boolean shouldApplyEnderpearlCooldown(Player player) {
        if (!enderpearlCooldownEnabled) {
            return false;
        }

        String worldName = player.getWorld().getName();
        if (!worldEnderpearlCooldowns.getOrDefault(worldName, true)) return false;

        return !(enderpearlCombatOnly && !plugin.isInCombat(player));
    }

    private boolean shouldApplyTridentCooldown(Player player) {
        if (!tridentCooldownEnabled) {
            return false;
        }

        String worldName = player.getWorld().getName();
        if (!worldTridentCooldowns.getOrDefault(worldName, true)) return false;

        return !(tridentCombatOnly && !plugin.isInCombat(player));
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!itemRestrictionsEnabled && !enderpearlBlockInCombat && !tridentBlockInCombat) return;
        Player player = event.getPlayer();
        if (!plugin.isCombatEnabledInWorld(player)) return;
        Material item = player.getInventory().getItemInMainHand().getType();
        World world = player.getWorld();
        
        if (item == Material.ENDER_PEARL) {
            if (enderpearlBlockInCombat && plugin.isInCombat(player)) {
                event.setCancelled(true);
                player.sendMessage(ChatUtil.parse(enderpearlCombatMessage));
            }
        }

        if (item == Material.TRIDENT) {
            if (plugin.getConfig().getBoolean("trident.banned_worlds." + world.getName(), false)) {
                event.setCancelled(true);
                player.sendMessage(ChatUtil.parse("&cTridents are disabled in this world!"));
                return;
            }
            if (tridentBlockInCombat && plugin.isInCombat(player)) {
                event.setCancelled(true);
                player.sendMessage(ChatUtil.parse(tridentCombatMessage));
            }
        }
    }
}