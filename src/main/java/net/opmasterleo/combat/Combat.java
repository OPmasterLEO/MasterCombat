package net.opmasterleo.combat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.opmasterleo.combat.api.MasterCombatAPIBackend;
import net.opmasterleo.combat.api.MasterCombatAPIProvider;
import net.opmasterleo.combat.api.events.MasterCombatLoadEvent;
import net.opmasterleo.combat.command.CombatCommand;
import net.opmasterleo.combat.command.PlayerCommandPreprocess;
import net.opmasterleo.combat.listener.*;
import net.opmasterleo.combat.listener.player.*;
import net.opmasterleo.combat.manager.*;
import net.opmasterleo.combat.listener.NewbieProtectionListener;
import net.opmasterleo.combat.listener.ItemRestrictionListener;
import net.opmasterleo.combat.util.ChatUtil;
import net.opmasterleo.combat.util.ConfigUtil;
import net.opmasterleo.combat.util.SchedulerUtil;
import net.opmasterleo.combat.util.WorldGuardUtil;

public class Combat extends JavaPlugin implements Listener {

    private static Combat instance;
    private final ConcurrentHashMap<UUID, CombatRecord> combatRecords = new ConcurrentHashMap<>(512, 0.75f, 64);
    private final ConcurrentHashMap<UUID, Long> lastActionBarUpdates = new ConcurrentHashMap<>(512, 0.75f, 64);
    
    private boolean enableWorldsEnabled;
    private Set<String> enabledWorlds;
    private boolean combatEnabled;
    private boolean glowingEnabled;
    private WorldGuardUtil worldGuardUtil;
    private EndCrystalListener endCrystalListener;
    private NewbieProtectionListener newbieProtectionListener;
    private CrystalManager crystalManager;
    private SuperVanishManager superVanishManager;
    private GlowManager glowManager;
    private boolean enderPearlEnabled;
    private long enderPearlDistance;
    private boolean disableElytraEnabled;
    private String disableElytraMsg;
    private String disableElytraType;
    private final Set<String> ignoredProjectiles = ConcurrentHashMap.newKeySet();
    private RespawnAnchorListener respawnAnchorListener;
    private BedExplosionListener bedExplosionListener;
    private ItemRestrictionListener itemRestrictionListener;
    
    private String prefix;
    private String nowInCombatMsg;
    private String nowInCombatType;
    private String noLongerInCombatMsg;
    private String noLongerInCombatType;
    private boolean debugEnabled;
    private String combatFormat;
    private boolean peInitialized;
    
    public static class CombatRecord {
        public final long expiry;
        public final UUID opponent;

        public CombatRecord(long expiry, UUID opponent) {
            this.expiry = expiry;
            this.opponent = opponent;
        }
    }

    private void loadConfigValues() {
        reloadConfig();
        combatEnabled = getConfig().getBoolean("General.combat-enabled", true);
        glowingEnabled = getConfig().getBoolean("General.CombatTagGlowing", false);
        enableWorldsEnabled = getConfig().getBoolean("EnabledWorlds.enabled", false);
        enabledWorlds = new HashSet<>(getConfig().getStringList("EnabledWorlds.worlds"));
        enderPearlEnabled = getConfig().getBoolean("EnderPearl.Enabled", false);
        enderPearlDistance = getConfig().getLong("EnderPearl.Distance", 0);
        debugEnabled = getConfig().getBoolean("debug", false);
        combatFormat = getConfig().getString("General.Format", "");
        
        if (getConfig().isConfigurationSection("General.disable-elytra")) {
            disableElytraEnabled = getConfig().getBoolean("General.disable-elytra.enabled", false);
            disableElytraMsg = getConfig().getString("General.disable-elytra.text", "");
            disableElytraType = getConfig().getString("General.disable-elytra.type", "chat");
        } else {
            disableElytraEnabled = getConfig().getBoolean("General.disable-elytra", false);
            disableElytraMsg = "";
            disableElytraType = "chat";
        }

        prefix = getConfig().getString("Messages.Prefix", "");
        
        if (getConfig().isConfigurationSection("Messages.NowInCombat")) {
            nowInCombatMsg = getConfig().getString("Messages.NowInCombat.text", "");
            nowInCombatType = getConfig().getString("Messages.NowInCombat.type", "chat");
        } else {
            nowInCombatMsg = getConfig().getString("Messages.NowInCombat", "");
            nowInCombatType = "chat";
        }
        
        if (getConfig().isConfigurationSection("Messages.NoLongerInCombat")) {
            noLongerInCombatMsg = getConfig().getString("Messages.NoLongerInCombat.text", "");
            noLongerInCombatType = getConfig().getString("Messages.NoLongerInCombat.type", "chat");
        } else {
            noLongerInCombatMsg = getConfig().getString("Messages.NoLongerInCombat", "");
            noLongerInCombatType = "chat";
        }

        ignoredProjectiles.clear();
        ignoredProjectiles.addAll(getConfig().getStringList("ignored-projectiles").stream()
            .map(String::toUpperCase)
            .collect(Collectors.toSet()));
    }

    private void initializeManagers() {
        if (Bukkit.getPluginManager().getPlugin("SuperVanish") != null) {
            superVanishManager = new SuperVanishManager();
        }
        
        crystalManager = new CrystalManager();
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                worldGuardUtil = new WorldGuardUtil(this);
                debug("WorldGuard integration enabled");
            } catch (Exception e) {
                getLogger().warning("Failed to initialize WorldGuard integration: " + e.getMessage());
            }
        } else {
            debug("WorldGuard not found, PvP region protection disabled");
        }
        
        if (glowingEnabled && isPacketEventsAvailable()) {
            try {
                glowManager = new GlowManager();
                debug("Glowing effect system enabled");
            } catch (Exception e) {
                glowingEnabled = false;
                getLogger().warning("Failed to initialize glowing system: " + e.getMessage());
            }
        } else if (glowingEnabled) {
            glowingEnabled = false;
            getLogger().warning("PacketEvents not found, glowing effect system disabled");
        }
    }
    
    private void registerCommands() {
        CombatCommand combatCommand = new CombatCommand();
        Objects.requireNonNull(getCommand("combat")).setExecutor(combatCommand);
        Objects.requireNonNull(getCommand("combat")).setTabCompleter(combatCommand);
        Objects.requireNonNull(getCommand("protection")).setExecutor(combatCommand);
        Objects.requireNonNull(getCommand("protection")).setTabCompleter(combatCommand);

        String disableCmd = getConfig().getString("NewbieProtection.settings.disableCommand", "removeprotect").toLowerCase();
        if (getCommand(disableCmd) != null) {
            getCommand(disableCmd).setExecutor(combatCommand);
            getCommand(disableCmd).setTabCompleter(combatCommand);
        }
    }

    private PlayerCommandPreprocess commandProcessor;
    
    public PlayerCommandPreprocess getCommandProcessor() {
        return commandProcessor;
    }

    private void registerListeners() {
        commandProcessor = new PlayerCommandPreprocess();
        Bukkit.getPluginManager().registerEvents(commandProcessor, this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerTeleportListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(), this);
        Bukkit.getPluginManager().registerEvents(new CustomDeathMessageListener(), this);
        Bukkit.getPluginManager().registerEvents(new SelfCombatListener(), this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new DirectCombatListener(), this);

        endCrystalListener = new EndCrystalListener();
        Bukkit.getPluginManager().registerEvents(endCrystalListener, this);
        if (getConfig().getBoolean("link-respawn-anchor", true)) {
            respawnAnchorListener = new RespawnAnchorListener();
            Bukkit.getPluginManager().registerEvents(respawnAnchorListener, this);
        }

        newbieProtectionListener = new NewbieProtectionListener();
        Bukkit.getPluginManager().registerEvents(newbieProtectionListener, this);
        if (getConfig().getBoolean("link-bed-explosions", true)) {
            bedExplosionListener = new BedExplosionListener();
            Bukkit.getPluginManager().registerEvents(bedExplosionListener, this);
        } else {
            bedExplosionListener = null;
        }
        
        itemRestrictionListener = new ItemRestrictionListener();
        Bukkit.getPluginManager().registerEvents(itemRestrictionListener, this);
        
        if (worldGuardUtil != null && getConfig().getBoolean("safezone_protection.enabled", true)) {
            worldGuardUtil.reloadConfig();
        }
    }
    
    private void initializeAPI() {
        MasterCombatAPIProvider.set(new MasterCombatAPIBackend(this));
        Bukkit.getPluginManager().callEvent(new MasterCombatLoadEvent());
    }

    @Override
    public void onDisable() {
        SchedulerUtil.setShuttingDown(true);
        Update.setShuttingDown(true);
        Update.cleanupTasks();
        
        if (glowManager != null) {
            glowManager.cleanup();
        }

        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}

        SchedulerUtil.cancelAllTasks(this);

        combatRecords.clear();
        lastActionBarUpdates.clear();
        try {
            if (peInitialized && PacketEvents.getAPI() != null) {
                PacketEvents.getAPI().terminate();
                debug("PacketEvents terminated successfully");
            } else {
                debug("Skipping PacketEvents terminate: not initialized");
            }
        } catch (Exception e) {
            debug("Error during PacketEvents shutdown: " + e.getMessage());
        }

        getLogger().info("MasterCombat shutdown complete.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        if (glowManager != null) {
            glowManager.trackPlayer(player);
        }
        
        if (player.isOp() && getConfig().getBoolean("update-notify-chat", false)) {
            Update.notifyOnPlayerJoin(player, this);
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        if (glowManager != null) {
            glowManager.untrackPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;

        Location location = victim.getLocation();
        Block anchorBlock = null;
        
        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();
        
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Block block = location.getWorld().getBlockAt(blockX + x, blockY + y, blockZ + z);
                    if (block.getType() == Material.RESPAWN_ANCHOR) {
                        anchorBlock = block;
                        x = y = z = 3;
                        break;
                    }
                }
            }
        }

        if (anchorBlock == null) return;

        UUID anchorId = UUID.nameUUIDFromBytes(anchorBlock.getLocation().toString().getBytes());
        Player activator = newbieProtectionListener.getAnchorActivator(anchorId);
        if (activator == null) return;

        if (newbieProtectionListener.isActuallyProtected(activator) &&
            !newbieProtectionListener.isActuallyProtected(victim)) {
            event.setCancelled(true);
            newbieProtectionListener.sendBlockedMessage(activator, newbieProtectionListener.getAnchorBlockMessage());
        }
    }

    public void directSetCombat(Player player, Player opponent) {
        if (!combatEnabled || player == null || opponent == null) return;
        
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
            opponent.getGameMode() == GameMode.CREATIVE || opponent.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (superVanishManager != null && 
            (superVanishManager.isVanished(player) || superVanishManager.isVanished(opponent))) {
            return;
        }

        UUID playerUUID = player.getUniqueId();
        UUID opponentUUID = opponent.getUniqueId();
        
        if (worldGuardUtil != null) {
            boolean playerInProtectedRegion = worldGuardUtil.isPvpDenied(player.getLocation());
            boolean opponentInProtectedRegion = !playerUUID.equals(opponentUUID) && worldGuardUtil.isPvpDenied(opponent.getLocation());
            if (playerInProtectedRegion || opponentInProtectedRegion) {
                return;
            }
        }

        long expiry = System.currentTimeMillis() + (getConfig().getLong("General.duration", 0) * 1000L);
        
        CombatRecord playerRecord = combatRecords.get(playerUUID);
        CombatRecord opponentRecord = combatRecords.get(opponentUUID);
        
        boolean playerWasInCombat = playerRecord != null;
        boolean opponentWasInCombat = !playerUUID.equals(opponentUUID) && opponentRecord != null;
        
        combatRecords.put(playerUUID, new CombatRecord(expiry, opponentUUID));
        
        if (!playerUUID.equals(opponentUUID)) {
            combatRecords.put(opponentUUID, new CombatRecord(expiry, playerUUID));
        }
        
        if (!playerWasInCombat && nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
            sendCombatMessage(player, nowInCombatMsg, nowInCombatType);
        }
        
        if (!playerUUID.equals(opponentUUID) && !opponentWasInCombat && nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
            sendCombatMessage(opponent, nowInCombatMsg, nowInCombatType);
        }

        if (glowingEnabled && glowManager != null) {
            if (!playerWasInCombat) glowManager.setGlowing(player, true);
            if (!playerUUID.equals(opponentUUID) && !opponentWasInCombat) glowManager.setGlowing(opponent, true);
        }
        lastActionBarUpdates.put(playerUUID, 0L);
        if (!playerUUID.equals(opponentUUID)) {
            lastActionBarUpdates.put(opponentUUID, 0L);
        }
        
        updateActionBar(player, expiry, System.currentTimeMillis());
        if (!playerUUID.equals(opponentUUID)) {
            updateActionBar(opponent, expiry, System.currentTimeMillis());
        }
    }
    
    public void forceCombatCleanup(UUID playerUUID) {
        if (playerUUID == null) return;
        CombatRecord record = combatRecords.remove(playerUUID);
        lastActionBarUpdates.remove(playerUUID);
        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null && glowingEnabled && glowManager != null) {
            glowManager.setGlowing(player, false);
        }

        if (record != null && record.opponent != null) {
            CombatRecord opponentRecord = combatRecords.get(record.opponent);
            if (opponentRecord != null && playerUUID.equals(opponentRecord.opponent)) {
                combatRecords.remove(record.opponent);
                lastActionBarUpdates.remove(record.opponent);
                if (glowingEnabled && glowManager != null) {
                    Player opponent = Bukkit.getPlayer(record.opponent);
                    if (opponent != null) {
                        glowManager.setGlowing(opponent, false);
                    }
                }
            }
        }
    }

    private void startCombatTimer() {
        final long timerInterval = isPacketEventsAvailable() ? 20L : 30L;
        final int BATCH_SIZE = 50;
        final AtomicInteger currentIndex = new AtomicInteger(0);
        final List<UUID> playerUUIDs = new ArrayList<>();
        final long[] lastBatchTime = {0};
        
        Runnable timerTask = () -> {
            long currentTime = System.currentTimeMillis();
            
            if (System.currentTimeMillis() - lastBatchTime[0] > 5000 || playerUUIDs.isEmpty()) {
                playerUUIDs.clear();
                playerUUIDs.addAll(combatRecords.keySet());
                currentIndex.set(0);
                lastBatchTime[0] = System.currentTimeMillis();
            }
            
            int startIdx = currentIndex.getAndAdd(BATCH_SIZE);
            int endIdx = Math.min(startIdx + BATCH_SIZE, playerUUIDs.size());
            
            for (int i = startIdx; i < endIdx; i++) {
                UUID uuid = playerUUIDs.get(i);
                CombatRecord record = combatRecords.get(uuid);
                if (record == null) continue;
                
                Player player = Bukkit.getPlayer(uuid);
                if (player == null) {
                    combatRecords.remove(uuid);
                    lastActionBarUpdates.remove(uuid);
                    continue;
                }

                if (currentTime >= record.expiry) {
                    handleCombatEnd(player);
                    lastActionBarUpdates.remove(uuid);
                } else {
                    Long lastUpdate = lastActionBarUpdates.get(uuid);
                    if (lastUpdate == null || currentTime - lastUpdate >= 250) {
                        updateActionBar(player, record.expiry, currentTime);
                        lastActionBarUpdates.put(uuid, currentTime);
                    }
                }
            }
            
            if (endIdx >= playerUUIDs.size()) {
                playerUUIDs.clear();
                currentIndex.set(0);
            }
        };

        try {
            SchedulerUtil.runTaskTimerAsync(this, timerTask, timerInterval, timerInterval);
        } catch (Exception e) {
            getLogger().warning("Failed to schedule combat timer: " + e.getMessage());
        }
    }

    private void handleCombatEnd(Player player) {
        UUID playerUUID = player.getUniqueId();
        CombatRecord record = combatRecords.remove(playerUUID);
        
        if (record == null) return;
        
        if (glowingEnabled && glowManager != null) {
            glowManager.setGlowing(player, false);
            if (record.opponent != null) {
                Player opponent = Bukkit.getPlayer(record.opponent);
                if (opponent != null) {
                    glowManager.setGlowing(opponent, false);
                }
            }
        }
        
        if (noLongerInCombatMsg != null && !noLongerInCombatMsg.isEmpty()) {
            sendNoLongerInCombatMessage(player, noLongerInCombatMsg, noLongerInCombatType);
        }
    }

    private void sendNoLongerInCombatMessage(Player player, String message, String type) {
        if (message == null || message.isEmpty()) return;
        net.kyori.adventure.text.Component component = ChatUtil.parse(prefix + message);
        switch (type == null ? "chat" : type.toLowerCase()) {
            case "actionbar":
                player.sendActionBar(component);
                break;
            case "both":
                player.sendMessage(component);
                player.sendActionBar(component);
                break;
            case "chat":
            default:
                player.sendMessage(component);
                break;
        }
    }
    
    public void sendCombatMessage(Player player, String message, String type) {
        if (message == null || message.isEmpty()) return;
        net.kyori.adventure.text.Component component = ChatUtil.parse(prefix + message);
        switch (type == null ? "chat" : type.toLowerCase()) {
            case "actionbar":
                player.sendActionBar(component);
                break;
            case "both":
                player.sendMessage(component);
                player.sendActionBar(component);
                break;
            case "chat":
            default:
                player.sendMessage(component);
                break;
        }
    }

    private void updateActionBar(Player player, long endTime, long currentTime) {
        if (player == null) return;
        long seconds = (endTime - currentTime + 999) / 1000;
        if (seconds <= 0) seconds = 0;
        if (combatFormat == null || combatFormat.isEmpty()) return;
        String message = combatFormat.replace("%seconds%", String.valueOf(seconds));
        net.kyori.adventure.text.Component component = ChatUtil.parse(message);
        try {
            player.sendActionBar(component);
        } catch (Exception ignored) {}
    }

    private boolean shouldBypass(Player player) {
        return (getConfig().getBoolean("General.ignore-op", true) && player.isOp()) 
            || player.getGameMode() == GameMode.CREATIVE 
            || player.getGameMode() == GameMode.SPECTATOR;
    }

    public boolean isCombatEnabledInWorld(Player player) {
        return !enableWorldsEnabled || enabledWorlds == null || enabledWorlds.contains(player.getWorld().getName());
    }

    public boolean isInCombat(Player player) {
        CombatRecord record = combatRecords.get(player.getUniqueId());
        return record != null && record.expiry > System.currentTimeMillis();
    }

    public void setCombat(Player player, Player opponent) {
        if (player == null || opponent == null) return;
        if (player.equals(opponent)) {
            if (getConfig().getBoolean("self-combat", false)) {
                forceSetCombat(player, player);
            }
            return;
        }

        if (!combatEnabled || !isCombatEnabledInWorld(player) || shouldBypass(player)) return;
        if (!canDamage(player, opponent)) return;

        forceSetCombat(player, opponent);
    }
    
    public void forceSetCombat(Player player, Player opponent) {
        if (!combatEnabled || player == null || !isCombatEnabledInWorld(player) || shouldBypass(player)) return;
        long expiry = System.currentTimeMillis() + (getConfig().getLong("General.duration", 0) * 1000L);
        UUID playerUUID = player.getUniqueId();
        UUID opponentUUID = opponent != null ? opponent.getUniqueId() : null;
        
        CombatRecord existing = combatRecords.get(playerUUID);
        boolean wasInCombat = existing != null && existing.expiry > System.currentTimeMillis();
        
        combatRecords.put(playerUUID, new CombatRecord(expiry, opponentUUID));
        
        if (!wasInCombat) {
            if (nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
                sendCombatMessage(player, nowInCombatMsg, nowInCombatType);
            }
            if (disableElytraEnabled) {
                if (player.isGliding()) player.setGliding(false);
                if (player.isFlying()) {
                    player.setFlying(false);
                    player.setAllowFlight(false);
                }
            }
            if (glowingEnabled && glowManager != null) {
                glowManager.setGlowing(player, true);
            }
        }
        
        lastActionBarUpdates.put(playerUUID, 0L);
        updateActionBar(player, expiry, System.currentTimeMillis());
        
        if (opponent != null && !opponent.equals(player)) {
            UUID oppUUID = opponent.getUniqueId();
            CombatRecord oppRecord = combatRecords.get(oppUUID);
            boolean oppWasInCombat = oppRecord != null && oppRecord.expiry > System.currentTimeMillis();
            
            combatRecords.put(oppUUID, new CombatRecord(expiry, playerUUID));
            
            if (!oppWasInCombat) {
                if (nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
                    sendCombatMessage(opponent, nowInCombatMsg, nowInCombatType);
                }
                if (glowingEnabled && glowManager != null) {
                    glowManager.setGlowing(opponent, true);
                }
                updateActionBar(opponent, expiry, System.currentTimeMillis());
            }
            
            lastActionBarUpdates.put(oppUUID, 0L);
            updateActionBar(opponent, expiry, System.currentTimeMillis());
        }
    }

    public boolean canDamage(Player attacker, Player victim) {
        if (attacker.equals(victim) && !getConfig().getBoolean("self-combat", false)) {
            return false;
        }

        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR ||
            victim.getGameMode() == GameMode.CREATIVE || victim.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        if (newbieProtectionListener != null) {
            boolean attackerProtected = newbieProtectionListener.isActuallyProtected(attacker);
            boolean victimProtected = newbieProtectionListener.isActuallyProtected(victim);

            if (attackerProtected && !victimProtected) return false;
            if (!attackerProtected && victimProtected) return false;
        }

        if (worldGuardUtil != null) {
            if (worldGuardUtil.isPvpDenied(attacker.getLocation()) || worldGuardUtil.isPvpDenied(victim.getLocation())) {
                return false;
            }
        }

        if (superVanishManager != null && 
            (superVanishManager.isVanished(attacker) || superVanishManager.isVanished(victim))) {
            return false;
        }

        return true;
    }

    public Player getCombatOpponent(Player player) {
        CombatRecord record = combatRecords.get(player.getUniqueId());
        if (record == null || record.opponent == null) {
            return null;
        }
        return Bukkit.getPlayer(record.opponent);
    }

    public void keepPlayerInCombat(Player player) {
        if (player != null) {
            CombatRecord record = combatRecords.get(player.getUniqueId());
            if (record != null) {
                combatRecords.put(player.getUniqueId(), new CombatRecord(
                    System.currentTimeMillis() + 1000 * getConfig().getLong("General.duration", 0),
                    record.opponent
                ));
            }
        }
    }

    public String getMessage(String key) {
        String message = getConfig().getString(key, "");
        return message != null ? message : "";
    }

    public void reloadCombatConfig() {
        reloadConfig();
        ConfigUtil.updateConfig(this);
        loadConfigValues();
        if (newbieProtectionListener != null) {
            newbieProtectionListener.reloadConfig();
        }
        
        PlayerCommandPreprocess playerCommandPreprocessListener = getCommandProcessor();
        if (playerCommandPreprocessListener != null) {
            playerCommandPreprocessListener.reloadBlockedCommands();
        }
        
        if (itemRestrictionListener != null) {
            itemRestrictionListener.reloadConfig();
        }
        
        if (worldGuardUtil != null) {
            worldGuardUtil.reloadConfig();
        }
        
        debug("Configuration reloaded successfully");
    }
    
    public boolean isDisableElytra() { return disableElytraEnabled; }
    public String getElytraDisabledMsg() { return disableElytraMsg; }
    public String getElytraDisabledType() { return disableElytraType; }
    public boolean isEnderPearlEnabled() { return enderPearlEnabled; }
    public long getEnderPearlDistance() { return enderPearlDistance; }
    public Set<String> getIgnoredProjectiles() { return ignoredProjectiles; }

    public void setCombatEnabled(boolean enabled) {
        this.combatEnabled = enabled;
    }

    public static Combat getInstance() {
        return instance;
    }

    public WorldGuardUtil getWorldGuardUtil() {
        return worldGuardUtil;
    }

    public void registerCrystalPlacer(Entity crystal, Player placer) {
        if (crystalManager != null) {
            crystalManager.setPlacer(crystal, placer);
        }
    }

    public NewbieProtectionListener getNewbieProtectionListener() {
        return newbieProtectionListener;
    }

    public Map<UUID, CombatRecord> getCombatRecords() {
        return combatRecords;
    }

    public CrystalManager getCrystalManager() {
        return crystalManager;
    }

    public SuperVanishManager getSuperVanishManager() {
        return superVanishManager;
    }

    public GlowManager getGlowManager() {
        if (!glowingEnabled) return null;
        return glowManager;
    }

    public boolean isCombatEnabled() {
        return combatEnabled;
    }

    public void safelyRegisterPacketListener(PacketListener listener) {
        try {
            if (isPacketEventsAvailable() && PacketEvents.getAPI() != null) {
                PacketEvents.getAPI().getEventManager().registerListener((PacketListenerCommon) listener);
            }
        } catch (Exception ignored) {}
    }

    public boolean isPacketEventsAvailable() {
        try {
            return Bukkit.getPluginManager().getPlugin("PacketEvents") != null &&
                   PacketEvents.getAPI() != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onLoad() {
        try {
            if (Bukkit.getPluginManager().getPlugin("PacketEvents") != null) {
                PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
                PacketEvents.getAPI().getSettings()
                    .reEncodeByDefault(true)
                    .checkForUpdates(false);
                PacketEvents.getAPI().load();
                debug("Successfully loaded PacketEvents");
            }
        } catch (Exception e) {
            getLogger().warning("Failed to initialize PacketEvents: " + e.getMessage());
            if (glowingEnabled) {
                glowingEnabled = false;
                getLogger().warning("Disabled glowing system due to PacketEvents initialization failure");
            }
        }
    }
    
    private final EntityManager entityManager = new EntityManager();

    public EntityManager getEntityManager() {
        return entityManager;
    }

    public static abstract class PacketListenerAdapter implements PacketListener {
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {}

        @Override
        public void onPacketSend(PacketSendEvent event) {}
    }

    private void sendStartupMessage() {
        String version = getPluginMeta().getVersion();
        String pluginName = getPluginMeta().getDisplayName();

        String apiType = SchedulerUtil.isFolia() ? "folia" : "bukkit";
        String serverJarName = Bukkit.getServer().getName();

        boolean worldGuardDetected = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
        boolean packetEventsLoaded = Bukkit.getPluginManager().getPlugin("PacketEvents") != null;

        Bukkit.getConsoleSender().sendMessage("§cINFO §8» §aWorldGuard " + (worldGuardDetected ? "loaded!" : "not loaded!"));
        Bukkit.getConsoleSender().sendMessage("§cINFO §8» §aPacketEvents " + (packetEventsLoaded ? "loaded!" : "not loaded!"));

        String displayText = pluginName.contains(version) ? pluginName : pluginName + " - v" + version;
        String asciiArt = "&b   ____                _           _               \n" +
            "&b  / ___|___  _ __ ___ | |__   __ _| |_             \n" +
            "&b | |   / _ \\| '_ ` _ \\| '_ \\ / _` | __|   " + displayText + "\n" +
            "&b | |__| (_) | | | | | | |_) | (_| | |_    Currently using " + apiType + " - " + serverJarName + "\n" +
            "&b  \\____\\___/|_| |_| |_|_.__/ \\__,_|\\__|   \n";

        for (String line : asciiArt.split("\n")) {
            Bukkit.getConsoleSender().sendMessage(ChatUtil.parse(line));
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUtil.updateConfig(this);
        instance = this;

        loadConfigValues();
        initializeManagers();

        if (isPacketEventsAvailable()) {
            try {
                PacketEvents.getAPI().init();
                peInitialized = true;
                debug("PacketEvents initialized successfully");
            } catch (Exception e) {
                peInitialized = false;
                getLogger().warning("Failed to initialize PacketEvents: " + e.getMessage());
                disablePacketEventsFeatures();
            }
        } else {
            peInitialized = false;
            getLogger().warning("PacketEvents not found. Disabling PacketEvents-dependent features.");
            disablePacketEventsFeatures();
        }

        registerCommands();
        registerListeners();
        startCombatTimer();
        initializeAPI();
        sendStartupMessage();

        new Metrics(this, 25701);
    }

    private void disablePacketEventsFeatures() {
        WorldGuardUtil.disablePacketEventsIntegration();
        EndCrystalListener.disablePacketEventsIntegration();
        BedExplosionListener.disablePacketEventsIntegration();
        PlayerMoveListener.disablePacketEventsIntegration();
        ItemRestrictionListener.disablePacketEventsIntegration();
        if (glowManager != null) {
            glowManager.disablePacketEventsIntegration();
        }
        getLogger().warning("PacketEvents features disabled. Some functionality will be limited.");
    }

    public void debug(String message) {
        if (debugEnabled) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }
}