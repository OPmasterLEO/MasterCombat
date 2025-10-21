package net.opmasterleo.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;

import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import net.opmasterleo.combat.api.MasterCombatAPIBackend;
import net.opmasterleo.combat.api.MasterCombatAPIProvider;
import net.opmasterleo.combat.api.events.MasterCombatLoadEvent;
import net.opmasterleo.combat.command.CombatCommand;
import net.opmasterleo.combat.command.PlayerCommandPreprocess;
import net.opmasterleo.combat.listener.BedExplosionListener;
import net.opmasterleo.combat.listener.CustomDeathMessageListener;
import net.opmasterleo.combat.listener.DirectCombatListener;
import net.opmasterleo.combat.listener.EndCrystalListener;
import net.opmasterleo.combat.listener.ItemRestrictionListener;
import net.opmasterleo.combat.listener.NewbieProtectionListener;
import net.opmasterleo.combat.listener.RespawnAnchorListener;
import net.opmasterleo.combat.listener.SelfCombatListener;
import net.opmasterleo.combat.listener.player.PlayerDeathListener;
import net.opmasterleo.combat.listener.player.PlayerQuitListener;
import net.opmasterleo.combat.listener.player.PlayerTeleportListener;
import net.opmasterleo.combat.manager.CrystalManager;
import net.opmasterleo.combat.manager.EntityManager;
import net.opmasterleo.combat.manager.GlowManager;
import net.opmasterleo.combat.manager.SuperVanishManager;
import net.opmasterleo.combat.manager.Update;
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
    private boolean packetEventsLoaded = false;
    private boolean pluginEnabled = true;
    private boolean folia = false;

    private ThreadPoolExecutor combatWorkerPool;
    private int maxWorkerPoolSize;
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final Map<String, Long> pendingTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> taskMetrics = new ConcurrentHashMap<>();
    private long lastMetricsLog = 0;
    private volatile double currentCpuUsage = 0.0;
    private volatile long lastCpuCheck = 0;
    private volatile long lastAdjustment = 0;
    private final AtomicInteger lastActiveTaskCount = new AtomicInteger(0);
    private final AtomicInteger adjustmentCycle = new AtomicInteger(0);
    private final java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    private static final long CPU_CHECK_INTERVAL = 5000;
    private static final long ADJUSTMENT_INTERVAL = 10000;
    private static final double HIGH_CPU_THRESHOLD = 0.75;
    private static final double LOW_CPU_THRESHOLD = 0.30;

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
        enabledWorlds = ConcurrentHashMap.newKeySet();
        enabledWorlds.addAll(getConfig().getStringList("EnabledWorlds.worlds"));
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
        crystalManager.initialize(this);
        
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                worldGuardUtil = new WorldGuardUtil(this);
                debug("WorldGuard integration enabled");
            } catch (Exception e) {
                getLogger().warning(() -> String.format("Failed to initialize WorldGuard integration: %s", e.getMessage()));
            }
        } else {
            debug("WorldGuard not found, PvP region protection disabled");
        }
        
        if (glowingEnabled && isPacketEventsAvailable()) {
            try {
                glowManager = new GlowManager();
                glowManager.initialize(this);
                debug("Glowing effect system enabled");
            } catch (Exception e) {
                glowingEnabled = false;
                getLogger().warning(() -> "Failed to initialize glowing system: " + e.getMessage());
            }
        } else if (glowingEnabled) {
            glowingEnabled = false;
            getLogger().warning("PacketEvents not found, glowing effect system disabled");
        }

        if (worldGuardUtil != null) {
            try {
                worldGuardUtil.initialize(this);
                debug("WorldGuard integration initialized");
            } catch (Exception e) {
                debug("Failed to initialize WorldGuard integration: " + e.getMessage());
            }
        }
    }

    private void registerCommands() {
        CombatCommand combatCommand = new CombatCommand();
        Objects.requireNonNull(getCommand("combat")).setExecutor(combatCommand);
        Objects.requireNonNull(getCommand("combat")).setTabCompleter(combatCommand);
        Objects.requireNonNull(getCommand("protection")).setExecutor(combatCommand);
        Objects.requireNonNull(getCommand("protection")).setTabCompleter(combatCommand);
        String disableCmdRaw = getConfig().getString("NewbieProtection.settings.disableCommand");
        String disableCmd = (disableCmdRaw == null || disableCmdRaw.isEmpty())
            ? "removeprotect"
            : disableCmdRaw;
        disableCmd = disableCmd.toLowerCase(Locale.ROOT);

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
        debug("Starting listener registration...");
        
        try {
            commandProcessor = new PlayerCommandPreprocess();
            Bukkit.getPluginManager().registerEvents(commandProcessor, this);
            debug("Registered PlayerCommandPreprocess listener successfully.");
        } catch (Exception e) {
            debug("Failed to register PlayerCommandPreprocess listener: " + e.getMessage());
        }

        try {
            Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);
            debug("Registered PlayerQuitListener successfully.");
        } catch (Exception e) {
            debug("Failed to register PlayerQuitListener: " + e.getMessage());
        }

        try {
            Bukkit.getPluginManager().registerEvents(new PlayerTeleportListener(), this);
            debug("Registered PlayerTeleportListener successfully.");
        } catch (Exception e) {
            debug("Failed to register PlayerTeleportListener: " + e.getMessage());
        }

        try {
            Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(), this);
            debug("Registered PlayerDeathListener successfully.");
        } catch (Exception e) {
            debug("Failed to register PlayerDeathListener: " + e.getMessage());
        }

        try {
            Bukkit.getPluginManager().registerEvents(new CustomDeathMessageListener(), this);
            debug("Registered CustomDeathMessageListener successfully.");
        } catch (Exception e) {
            debug("Failed to register CustomDeathMessageListener: " + e.getMessage());
        }

        try {
            Bukkit.getPluginManager().registerEvents(new SelfCombatListener(), this);
            debug("Registered SelfCombatListener successfully.");
        } catch (Exception e) {
            debug("Failed to register SelfCombatListener: " + e.getMessage());
        }

        try {
            Bukkit.getPluginManager().registerEvents(this, this);
            debug("Registered Combat as a listener successfully.");
        } catch (Exception e) {
            debug("Failed to register Combat as a listener: " + e.getMessage());
        }

        try {
            Bukkit.getPluginManager().registerEvents(new DirectCombatListener(), this);
            debug("Registered DirectCombatListener successfully.");
        } catch (Exception e) {
            debug("Failed to register DirectCombatListener: " + e.getMessage());
        }

        try {
            endCrystalListener = new EndCrystalListener();
            endCrystalListener.initialize(this);
            Bukkit.getPluginManager().registerEvents(endCrystalListener, this);
            debug("Registered EndCrystalListener successfully.");
        } catch (Exception e) {
            debug("Failed to register EndCrystalListener: " + e.getMessage());
        }

        if (getConfig().getBoolean("link-respawn-anchor", true)) {
            try {
                respawnAnchorListener = new RespawnAnchorListener(this);
                Bukkit.getPluginManager().registerEvents(respawnAnchorListener, this);
                debug("Registered RespawnAnchorListener successfully.");
            } catch (Exception e) {
                debug("Failed to register RespawnAnchorListener: " + e.getMessage());
            }
        } else {
            debug("RespawnAnchorListener is disabled in the configuration.");
        }

        try {
            newbieProtectionListener = new NewbieProtectionListener();
            Bukkit.getPluginManager().registerEvents(newbieProtectionListener, this);
            debug("Registered NewbieProtectionListener successfully.");
        } catch (Exception e) {
            debug("Failed to register NewbieProtectionListener: " + e.getMessage());
        }

        if (getConfig().getBoolean("link-bed-explosions", true)) {
            try {
                bedExplosionListener = new BedExplosionListener();
                bedExplosionListener.initialize(this);
                Bukkit.getPluginManager().registerEvents(bedExplosionListener, this);
                debug("Registered BedExplosionListener successfully.");
            } catch (Exception e) {
                debug("Failed to register BedExplosionListener: " + e.getMessage());
            }
        } else {
            debug("BedExplosionListener is disabled in the configuration.");
        }

        try {
            itemRestrictionListener = new ItemRestrictionListener();
            Bukkit.getPluginManager().registerEvents(itemRestrictionListener, this);
            debug("Registered ItemRestrictionListener successfully.");
        } catch (Exception e) {
            debug("Failed to register ItemRestrictionListener: " + e.getMessage());
        }

        if (worldGuardUtil != null && getConfig().getBoolean("safezone_protection.enabled", true)) {
            try {
                worldGuardUtil.reloadConfig();
                debug("WorldGuard safezone protection reloaded successfully.");
            } catch (Exception e) {
                debug("Failed to reload WorldGuard safezone protection: " + e.getMessage());
            }
        }
    }
    
    private void initializeAPI() {
        MasterCombatAPIProvider.set(new MasterCombatAPIBackend(this));
        Bukkit.getPluginManager().callEvent(new MasterCombatLoadEvent());
    }

    @Override
    public void onDisable() {
        pluginEnabled = false;
        if (combatWorkerPool != null) {
            combatWorkerPool.shutdown();
            
            try {
                if (!combatWorkerPool.awaitTermination(2000, TimeUnit.MILLISECONDS)) {
                    debug(String.format("Force shutting down thread pool with %d active tasks", combatWorkerPool.getActiveCount()));
                    combatWorkerPool.shutdownNow();
                    combatWorkerPool.awaitTermination(500, TimeUnit.MILLISECONDS);
                } else {
                    debug("Thread pool shut down successfully");
                }
            } catch (InterruptedException ignored) {
                combatWorkerPool.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                combatWorkerPool = null;
                pendingTasks.clear();
                taskMetrics.clear();
            }
        }

        if (packetEventsLoaded) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterAllListeners();
                debug("Packet listeners unregistered successfully.");
            } catch (Exception e) {
                debug(String.format("Error unregistering packet listeners: %s", e.getMessage()));
            }
        }

        cleanUpPlayerData();

        if (packetEventsLoaded) {
            try {
                PacketEvents.getAPI().terminate();
                debug("PacketEvents terminated successfully.");
            } catch (Exception e) {
                debug(String.format("Error during PacketEvents termination: %s", e.getMessage()));
            }
        }

        SchedulerUtil.setShuttingDown(true);
        Update.setShuttingDown(true);
        Update.cleanupTasks();
        if (glowManager != null) {
            try {
                glowManager.cleanup();
            } catch (Exception e) {
                debug(String.format("Error cleaning up GlowManager: %s", e.getMessage()));
            }
        }

        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}

        try {
            SchedulerUtil.cancelAllTasks(this);
        } catch (Exception e) {
            debug(String.format("Error canceling tasks: %s", e.getMessage()));
        }

        combatRecords.clear();
        lastActionBarUpdates.clear();

        getLogger().info("MasterCombat shutdown complete.");
    }

    private void cleanUpPlayerData() {
        combatRecords.clear();
        lastActionBarUpdates.clear();
        if (glowManager != null) {
            glowManager.cleanup();
        }
        debug("Player data cleaned up successfully.");
    }

    public boolean isPluginEnabled() {
        return pluginEnabled;
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
        boolean playerWasInCombat = playerRecord != null;
        boolean isSamePlayer = playerUUID.equals(opponentUUID);
        
        combatRecords.put(playerUUID, new CombatRecord(expiry, opponentUUID));
        
        boolean opponentWasInCombat = false;
        if (!isSamePlayer) {
            CombatRecord opponentRecord = combatRecords.put(opponentUUID, new CombatRecord(expiry, playerUUID));
            opponentWasInCombat = opponentRecord != null;
        }
        
        if (!playerWasInCombat && nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
            sendCombatMessage(player, nowInCombatMsg, nowInCombatType);
        }
        
        if (!isSamePlayer && !opponentWasInCombat && nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
            sendCombatMessage(opponent, nowInCombatMsg, nowInCombatType);
        }

        if (glowingEnabled && glowManager != null) {
            if (!playerWasInCombat) glowManager.setGlowing(player, true);
            if (!isSamePlayer && !opponentWasInCombat) glowManager.setGlowing(opponent, true);
        }
        lastActionBarUpdates.put(playerUUID, 0L);
        if (!isSamePlayer) {
            lastActionBarUpdates.put(opponentUUID, 0L);
        }
        
        long currentTime = System.currentTimeMillis();
        updateActionBar(player, expiry, currentTime);
        if (!isSamePlayer) {
            updateActionBar(opponent, expiry, currentTime);
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

    private double getCpuUsage() {
        long now = System.currentTimeMillis();
        if (now - lastCpuCheck < CPU_CHECK_INTERVAL) {
            return currentCpuUsage;
        }
        
        lastCpuCheck = now;
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double usage = sunBean.getProcessCpuLoad();
                if (usage >= 0.0) {
                    currentCpuUsage = usage;
                    return usage;
                }
            }

            int currentActive = combatWorkerPool.getActiveCount();
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            currentCpuUsage = Math.min(1.0, (double) currentActive / availableProcessors);
            return currentCpuUsage;
        } catch (Exception e) {
            return Math.min(0.5, (double) combatWorkerPool.getActiveCount() / maxWorkerPoolSize);
        }
    }
    
    private void adjustThreadPoolDynamically() {
        long now = System.currentTimeMillis();
        if (now - lastAdjustment < ADJUSTMENT_INTERVAL) {
            return;
        }
        
        lastAdjustment = now;
        int currentActive = combatWorkerPool.getActiveCount();
        int currentPoolSize = combatWorkerPool.getCorePoolSize();
        int currentMaxSize = combatWorkerPool.getMaximumPoolSize();
        int queueSize = combatWorkerPool.getQueue().size();
        double cpuUsage = getCpuUsage();

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int minThreads = Math.max(1, availableProcessors / 4);
        int maxThreads = Math.max(minThreads, folia ? Math.min(2, availableProcessors - 1) : availableProcessors);
        int newCoreSize = currentPoolSize;
        int newMaxSize = currentMaxSize;
        boolean highLoad = (queueSize > 5) || (currentActive > currentPoolSize * 0.8) || (cpuUsage > HIGH_CPU_THRESHOLD);
        boolean lowLoad = (queueSize == 0) && (currentActive < currentPoolSize * 0.3) && (cpuUsage < LOW_CPU_THRESHOLD);
        
        if (highLoad && currentMaxSize < maxThreads) {
            newMaxSize = Math.min(maxThreads, currentMaxSize + 1);
            newCoreSize = Math.min(newMaxSize, currentPoolSize + 1);
            
            if (debugEnabled) {
                debug(String.format("Scaling UP thread pool - CPU: %.2f%%, Queue: %d, Active: %d/%d -> %d/%d", 
                    cpuUsage * 100, queueSize, currentActive, currentMaxSize, newCoreSize, newMaxSize));
            }
        } else if (lowLoad && currentPoolSize > minThreads) {
            newCoreSize = Math.max(minThreads, currentPoolSize - 1);
            newMaxSize = Math.max(newCoreSize, currentMaxSize - 1);
            
            if (debugEnabled) {
                debug(String.format("Scaling DOWN thread pool - CPU: %.2f%%, Queue: %d, Active: %d/%d -> %d/%d", 
                    cpuUsage * 100, queueSize, currentActive, currentMaxSize, newCoreSize, newMaxSize));
            }
        }

        if (newMaxSize != currentMaxSize || newCoreSize != currentPoolSize) {
            try {
                combatWorkerPool.setMaximumPoolSize(newMaxSize);
                combatWorkerPool.setCorePoolSize(newCoreSize);
                maxWorkerPoolSize = newMaxSize;
            } catch (Exception e) {
                if (debugEnabled) {
                    debug("Failed to adjust thread pool size: " + e.getMessage());
                }
            }
        }
        
        lastActiveTaskCount.set(currentActive);
        adjustmentCycle.incrementAndGet();
    }
    
    private void startThreadPoolMetricsTask() {
        SchedulerUtil.runTaskTimerAsync(this, () -> {
            long now = System.currentTimeMillis();
            int activeCount = combatWorkerPool.getActiveCount();
            int poolSize = combatWorkerPool.getPoolSize();
            int queueSize = combatWorkerPool.getQueue().size();
            int tasksCompleted = (int)combatWorkerPool.getCompletedTaskCount();
            adjustThreadPoolDynamically();
            if (activeCount > 0 || poolSize > corePoolSize() || now - lastMetricsLog > 300000) {
                if (debugEnabled) {
                    debug(String.format("Thread pool stats - Active: %d, Size: %d/%d, Queue: %d, Completed: %d, Pending: %d, CPU: %.2f%%", 
                        activeCount, poolSize, maxWorkerPoolSize, queueSize, tasksCompleted, pendingTasks.size(), currentCpuUsage * 100));
                    
                    if (!taskMetrics.isEmpty()) {
                        StringBuilder metricsLog = new StringBuilder("Task execution times (ms): ");
                        for (Map.Entry<String, Long> entry : taskMetrics.entrySet()) {
                            metricsLog.append(entry.getKey()).append("=").append(entry.getValue()).append(", ");
                        }
                        debug(metricsLog.substring(0, Math.min(metricsLog.length(), 100)) + (metricsLog.length() > 100 ? "..." : ""));
                    }
                }
                lastMetricsLog = now;
                pendingTasks.entrySet().removeIf(entry -> now - entry.getValue() > 10000);
                if (taskMetrics.size() > 100) {
                    taskMetrics.clear();
                }
            }
        }, 20 * 20, 20 * 20);
    }
    
    private int corePoolSize() {
        return Math.max(1, maxWorkerPoolSize / 2);
    }

    private void startCombatTimer() {
        final long timerInterval = isPacketEventsAvailable() ? 20L : 30L;
        final boolean useWorkers = !folia;
        final int BATCH_SIZE = 50;
        final List<UUID> playerUUIDs = new ArrayList<>(512);

        Runnable timerTask = () -> {
            playerUUIDs.clear();
            playerUUIDs.addAll(combatRecords.keySet());
            if (playerUUIDs.isEmpty()) return;

            final int total = playerUUIDs.size();
            final int currentPoolSize = combatWorkerPool.getPoolSize();
            final int activeThreads = combatWorkerPool.getActiveCount();
            final double loadRatio = currentPoolSize > 0 ? (double) activeThreads / currentPoolSize : 0.0;
            final int baseBatchSize = loadRatio > 0.7 ? 15 : (loadRatio > 0.4 ? 30 : BATCH_SIZE);
            final int dynamicBatchSize = Math.min(baseBatchSize, Math.max(5, total / Math.max(1, currentPoolSize)));

            for (int start = 0; start < total; start += dynamicBatchSize) {
                final int s = start;
                final int e = Math.min(start + dynamicBatchSize, total);
                
                final String batchKey = "combat-batch-" + s + "-" + e;
                
                if (pendingTasks.containsKey(batchKey) && 
                    System.currentTimeMillis() - pendingTasks.get(batchKey) < 500) {
                    continue;
                }
                
                pendingTasks.put(batchKey, System.currentTimeMillis());
                
                if (useWorkers && combatWorkerPool != null && !combatWorkerPool.isShutdown()) {
                    combatWorkerPool.submit(() -> {
                        long startTime = System.currentTimeMillis();
                        activeTaskCount.incrementAndGet();
                        
                        try {
                            final int batchSize = e - s;
                            final List<UUID> toEnd = new ArrayList<>(batchSize);
                            final List<UUID> toActionbar = new ArrayList<>(batchSize);
                            final Map<UUID, Long> actionbarExpiry = new HashMap<>(batchSize);

                            long currentTime = System.currentTimeMillis();

                            for (int i = s; i < e; i++) {
                                if (i >= playerUUIDs.size()) break;
                                UUID uuid = playerUUIDs.get(i);
                                CombatRecord record = combatRecords.get(uuid);
                                if (record == null) continue;

                                if (currentTime >= record.expiry) {
                                    toEnd.add(uuid);
                                } else {
                                    Long lastUpdate = lastActionBarUpdates.get(uuid);
                                    if (lastUpdate == null || currentTime - lastUpdate >= 250) {
                                        toActionbar.add(uuid);
                                        actionbarExpiry.put(uuid, record.expiry);
                                    }
                                }
                            }

                            if (!toEnd.isEmpty() || !toActionbar.isEmpty()) {
                                SchedulerUtil.runTask(Combat.this, () -> {
                                    long syncNow = System.currentTimeMillis();
                                    for (UUID uuid : toEnd) {
                                        Player player = Bukkit.getPlayer(uuid);
                                        if (player != null) {
                                            handleCombatEnd(player);
                                        }
                                        combatRecords.remove(uuid);
                                        lastActionBarUpdates.remove(uuid);
                                    }
                                    if (!toActionbar.isEmpty()) {
                                        if (toActionbar.size() > 10) {
                                            toActionbar.sort((a, b) -> {
                                                Long expA = actionbarExpiry.get(a);
                                                Long expB = actionbarExpiry.get(b);
                                                if (expA == null) return 1;
                                                if (expB == null) return -1;
                                                return expA.compareTo(expB);
                                            });
                                        }
                                        
                                        for (UUID uuid : toActionbar) {
                                            Player player = Bukkit.getPlayer(uuid);
                                            if (player != null) {
                                                Long expiry = actionbarExpiry.get(uuid);
                                                if (expiry != null) {
                                                    updateActionBar(player, expiry, syncNow);
                                                    lastActionBarUpdates.put(uuid, syncNow);
                                                }
                                            }
                                        }
                                    }
                                });
                            }
                        } finally {
                            pendingTasks.remove(batchKey);
                            activeTaskCount.decrementAndGet();
                            long execTime = System.currentTimeMillis() - startTime;
                            taskMetrics.put(batchKey + "-" + execTime, execTime);
                        }
                    });
                } else {
                    long currentTime = System.currentTimeMillis();
                    for (int i = s; i < e; i++) {
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
                }
            }
        };

        try {
            if (useWorkers) {
                SchedulerUtil.runTaskTimerAsync(this, timerTask, timerInterval, timerInterval);
            } else {
                SchedulerUtil.runTaskTimer(this, timerTask, timerInterval, timerInterval);
            }
        } catch (Exception e) {
            getLogger().warning(() -> "Failed to schedule combat timer: " + e.getMessage());
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

    public void sendCombatMessage(Player player, String message, String type) {
        if (message == null || message.isEmpty() || player == null) return;
        
        try {
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
        } catch (Exception e) {
            player.sendMessage(prefix + message);
        }
    }

    private void sendNoLongerInCombatMessage(Player player, String message, String type) {
        if (message == null || message.isEmpty() || player == null) return;
        
        try {
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
        } catch (Exception e) {
            player.sendMessage(prefix + message);
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
        long currentTime = System.currentTimeMillis();
        long expiry = currentTime + (getConfig().getLong("General.duration", 0) * 1000L);
        UUID playerUUID = player.getUniqueId();
        UUID opponentUUID = opponent != null ? opponent.getUniqueId() : null;
        
        CombatRecord existing = combatRecords.get(playerUUID);
        boolean wasInCombat = existing != null && existing.expiry > currentTime;
        
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
        updateActionBar(player, expiry, currentTime);
        
        if (opponent != null && !opponent.equals(player)) {
            UUID oppUUID = opponent.getUniqueId();
            CombatRecord oppRecord = combatRecords.get(oppUUID);
            boolean oppWasInCombat = oppRecord != null && oppRecord.expiry > currentTime;
            
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
            updateActionBar(opponent, expiry, currentTime);
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
        ConfigUtil.reloadConfigSafely(this);
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

    public RespawnAnchorListener getRespawnAnchorListener() {
        return respawnAnchorListener;
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
                debug("Successfully initialized PacketEvents settings");
            }
        } catch (Exception e) {
            getLogger().warning(() -> String.format("Failed to initialize PacketEvents settings: %s", e.getMessage()));
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

    public static Object createWrapperPlayServerBlockChange(Object blockPosition, Object wrappedBlockStateOrGlobalId) {
        try {
            final ClassLoader cl = Combat.class.getClassLoader();
            Class<?> packetCls;
            try {
                packetCls = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange", true, cl);
            } catch (ClassNotFoundException e) {
                return null;
            }
            Class<?> vectorCls;
            try {
                vectorCls = Class.forName("com.github.retrooper.packetevents.util.Vector3i", true, cl);
            } catch (ClassNotFoundException cnf) {
                vectorCls = (blockPosition != null) ? blockPosition.getClass() : null;
            }

            try {
                Class<?> wrappedStateCls = Class.forName("com.github.retrooper.packetevents.wrapper.block.WrappedBlockState", true, cl);
                if (vectorCls != null && wrappedStateCls != null) {
                    try {
                        java.lang.reflect.Constructor<?> ctor = packetCls.getConstructor(vectorCls, wrappedStateCls);
                        Object stateArg = wrappedBlockStateOrGlobalId;
                        if (stateArg != null && !wrappedStateCls.isInstance(stateArg)) {
                            try {
                                java.lang.reflect.Method ofMethod = wrappedStateCls.getMethod("fromGlobalId", int.class);
                                if (wrappedBlockStateOrGlobalId instanceof Number number) {
                                    int gid = number.intValue();
                                    if (gid >= 0) {
                                        try {
                                            stateArg = ofMethod.invoke(null, gid);
                                        } catch (java.lang.reflect.InvocationTargetException e) {
                                        }
                                    }
                                }
                            } catch (NoSuchMethodException | IllegalAccessException ignored) {}
                        }
                        if (stateArg != null && wrappedStateCls.isInstance(stateArg)) {
                            try {
                                return ctor.newInstance(blockPosition, stateArg);
                            } catch (InstantiationException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
                                return null;
                            }
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }

            try {
                java.lang.reflect.Constructor<?> ctor2 = packetCls.getConstructor(vectorCls, int.class);
                int gid = -1;
                if (wrappedBlockStateOrGlobalId instanceof Number number) {
                    gid = number.intValue();
                } else if (wrappedBlockStateOrGlobalId != null) {
                    try {
                        java.lang.reflect.Method m = wrappedBlockStateOrGlobalId.getClass().getMethod("getGlobalId");
                        try {
                            Object v = m.invoke(wrappedBlockStateOrGlobalId);
                            if (v instanceof Number vNumber) gid = vNumber.intValue();
                        } catch (java.lang.reflect.InvocationTargetException e) {
                        }
                    } catch (NoSuchMethodException | IllegalAccessException ignored) {}
                }
                if (gid >= 0) {
                    try {
                        return ctor2.newInstance(blockPosition, gid);
                    } catch (InstantiationException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
                        return null;
                    }
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (IllegalStateException | SecurityException ignored) {
        }
        return null;
    }

    private void sendStartupMessage() {
        String version = getPluginMeta().getVersion();
        String pluginName = getPluginMeta().getDisplayName();

        String apiType = SchedulerUtil.isFolia() ? "folia" : "bukkit";
        String serverJarName = Bukkit.getServer().getName();

        boolean worldGuardDetected = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
        boolean isPacketEventsPresent = Bukkit.getPluginManager().getPlugin("PacketEvents") != null;

        Bukkit.getConsoleSender().sendMessage(ChatUtil.parse("&cINFO &8» &aWorldGuard " + (worldGuardDetected ? "loaded!" : "not loaded!")));
        Bukkit.getConsoleSender().sendMessage(ChatUtil.parse("&cINFO &8» &aPacketEvents " + (isPacketEventsPresent ? "loaded!" : "not loaded!")));

        String displayText = pluginName.contains(version) ? pluginName : pluginName + " - v" + version;
        String[] asciiLines = {
            "&b   ____                _           _               ",
            "&b  / ___|___  _ __ ___ | |__   __ _| |_             ",
            "&b | |   / _ \\| '_ ` _ \\| '_ \\ / _` | __|   " + displayText,
            "&b | |__| (_) | | | | | | |_) | (_| | |_    Currently using " + apiType + " - " + serverJarName,
            "&b  \\____\\___/|_| |_| |_|_.__/ \\__,_|\\__|   "
        };

        for (String line : asciiLines) {
            Bukkit.getConsoleSender().sendMessage(ChatUtil.parse(line));
        }
        try {
            String threadingMsg;
            if (maxWorkerPoolSize > 1) {
                threadingMsg = "&aUsing multi-threaded system - using " + maxWorkerPoolSize + " worker threads";
            } else {
                threadingMsg = "&eUsing single-threaded system - using " + maxWorkerPoolSize + " worker thread";
            }
            Bukkit.getConsoleSender().sendMessage(ChatUtil.parse(threadingMsg));
        } catch (Throwable ignored) {}
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        boolean configUpdated = ConfigUtil.updateConfig(this);
        
        if (configUpdated) {
            getLogger().info("Config updated successfully");
        }
        
        int pluginId = 25701;
        @SuppressWarnings("unused")
        Metrics metrics = new Metrics(this, pluginId);

        int cpus = Runtime.getRuntime().availableProcessors();
        folia = SchedulerUtil.isFolia();
        if (!folia) {
            maxWorkerPoolSize = Math.max(2, Math.min(cpus, 8));
        } else {
            // For Folia, be more conservative
            maxWorkerPoolSize = Math.max(1, Math.min(2, cpus - 1));
        }
        int corePoolSize = Math.max(1, Math.min(maxWorkerPoolSize / 3, 2));
        
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadCount = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MasterCombat-Worker-" + threadCount.getAndIncrement());
                t.setDaemon(true);
                if (threadCount.get() <= corePoolSize) {
                    t.setPriority(Thread.NORM_PRIORITY);
                } else {
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                }
                return t;
            }
        };
        
        combatWorkerPool = new ThreadPoolExecutor(
            corePoolSize, maxWorkerPoolSize,
            30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        combatWorkerPool.allowCoreThreadTimeOut(true);
        startThreadPoolMetricsTask();

        try {
            if (isPacketEventsAvailable() && !PacketEvents.getAPI().isLoaded()) {
                try {
                    PacketEvents.getAPI().load();
                    packetEventsLoaded = true;
                    debug("PacketEvents loaded successfully");
                } catch (Exception e) {
                    getLogger().warning(() -> String.format("Failed to load PacketEvents: %s", e.getMessage()));
                    packetEventsLoaded = false;
                }
            }
        } catch (Exception e) {
            getLogger().warning(() -> String.format("Failed to initialize PacketEvents settings: %s", e.getMessage()));
            if (glowingEnabled) {
                glowingEnabled = false;
                getLogger().warning("Disabled glowing system due to PacketEvents initialization failure");
            }
        }

        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServerInitEvent");
            try {
                final Class<?> regionInitClass = Class.forName("io.papermc.paper.threadedregions.RegionizedServerInitEvent");
                final Listener listenerHolder = new Listener() {};
                final EventExecutor executor = (listener, event) -> {
                    if (event != null && event.getClass() == regionInitClass) {
                        try {
                            if (postInitDone.compareAndSet(false, true)) {
                                postInit();
                            }
                        } finally {
                            try {
                                HandlerList.unregisterAll(listenerHolder);
                            } catch (Throwable ignored) {}
                        }
                    }
                };

                registerRegionInitListener(regionInitClass, listenerHolder, executor);
                debug("Paper RegionizedServerInitEvent detected - deferring plugin initialization until regionized server init.");
                SchedulerUtil.runTaskLater(this, () -> {
                    if (postInitDone.compareAndSet(false, true)) {
                        debug("RegionizedServerInitEvent not received in time - running deferred initialization fallback.");
                        try { HandlerList.unregisterAll(listenerHolder); } catch (Throwable ignored) {}
                        postInit();
                    }
                }, 20L * 5);
            } catch (ClassNotFoundException cnf) {
                if (postInitDone.compareAndSet(false, true)) postInit();
            }
        } catch (ClassNotFoundException ex) {
            if (postInitDone.compareAndSet(false, true)) postInit();
        }
    }

    private void postInit() {
        loadConfigValues();
        initializeManagers();
        registerCommands();
        registerListeners();
        startCombatTimer();
        initializeAPI();
        sendStartupMessage();

        try {
            if (packetEventsLoaded) {
                PacketEvents.getAPI().init();
                debug("PacketEvents initialized successfully");
            }
        } catch (Exception e) {
            getLogger().warning(() -> String.format("Failed to initialize PacketEvents: %s", e.getMessage()));
        }
    }

    public void debug(String message) {
        if (debugEnabled) {
            getLogger().info(() -> "[DEBUG] " + message);
        }
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public String getPrefix() {
        return prefix;
    }

    public Executor getCombatWorkerPool() {
        return combatWorkerPool;
    }
    
    /**
     * Gets comprehensive thread pool status for monitoring
     */
    public String getThreadPoolStatus() {
        if (combatWorkerPool == null) {
            return "Thread pool not initialized";
        }
        
        return String.format("Thread Pool Status - Core: %d, Max: %d, Active: %d, Queue: %d, CPU: %.1f%%, Completed: %d",
            combatWorkerPool.getCorePoolSize(),
            combatWorkerPool.getMaximumPoolSize(), 
            combatWorkerPool.getActiveCount(),
            combatWorkerPool.getQueue().size(),
            currentCpuUsage * 100,
            combatWorkerPool.getCompletedTaskCount()
        );
    }

    private final java.util.concurrent.atomic.AtomicBoolean postInitDone = new java.util.concurrent.atomic.AtomicBoolean(false);

    @SuppressWarnings("unchecked")
    private void registerRegionInitListener(Class<?> regionInitClass, Listener listenerHolder, EventExecutor executor) {
        Bukkit.getPluginManager().registerEvent((Class<? extends Event>) regionInitClass, listenerHolder, EventPriority.NORMAL, executor, this);
    }
}