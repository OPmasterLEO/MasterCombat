package net.opmasterleo.combat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import net.opmasterleo.combat.api.MasterCombatAPIBackend;
import net.opmasterleo.combat.api.MasterCombatAPIProvider;
import net.opmasterleo.combat.api.events.MasterCombatLoadEvent;
import net.opmasterleo.combat.command.CombatCommand;
import net.opmasterleo.combat.listener.CustomDeathMessageListener;
import net.opmasterleo.combat.listener.BedExplosionListener;
import net.opmasterleo.combat.listener.EndCrystalListener;
import net.opmasterleo.combat.listener.EntityPlaceListener;
import net.opmasterleo.combat.listener.PlayerCommandPreprocessListener;
import net.opmasterleo.combat.listener.PlayerDeathListener;
import net.opmasterleo.combat.listener.PlayerMoveListener;
import net.opmasterleo.combat.listener.PlayerQuitListener;
import net.opmasterleo.combat.listener.PlayerTeleportListener;
import net.opmasterleo.combat.listener.SelfCombatListener;
import net.opmasterleo.combat.listener.NewbieProtectionListener;
import net.opmasterleo.combat.listener.RespawnAnchorListener;
import net.opmasterleo.combat.manager.CrystalManager;
import net.opmasterleo.combat.manager.GlowManager;
import net.opmasterleo.combat.manager.WorldGuardUtil;
import net.opmasterleo.combat.manager.SuperVanishManager;
import net.opmasterleo.combat.manager.EntityManager;
import net.opmasterleo.combat.util.SchedulerUtil;
import net.opmasterleo.combat.manager.Update;
import net.opmasterleo.combat.util.ConfigUtil;

public class Combat extends JavaPlugin implements Listener {

    private static Combat instance;
    private final ConcurrentHashMap<UUID, Long> combatPlayers = new ConcurrentHashMap<>(512, 0.75f, 64);
    private final ConcurrentHashMap<UUID, UUID> combatOpponents = new ConcurrentHashMap<>(512, 0.75f, 64);
    private final ConcurrentHashMap<UUID, Long> lastActionBarSeconds = new ConcurrentHashMap<>(512, 0.75f, 64);
    
    private boolean enableWorldsEnabled;
    private List<String> enabledWorlds;
    private boolean combatEnabled;
    private boolean glowingEnabled;
    private WorldGuardUtil worldGuardUtil;
    private PlayerMoveListener playerMoveListener;
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
    
    private String prefix;
    private String nowInCombatMsg;
    private String noLongerInCombatMsg;
    private String noLongerInCombatType;

    private void loadConfigValues() {
        combatEnabled = getConfig().getBoolean("General.combat-enabled", true);
        glowingEnabled = getConfig().getBoolean("General.CombatTagGlowing", false);
        enableWorldsEnabled = getConfig().getBoolean("EnabledWorlds.enabled", false);
        enabledWorlds = getConfig().getStringList("EnabledWorlds.worlds");
        enderPearlEnabled = getConfig().getBoolean("EnderPearl.Enabled", false);
        enderPearlDistance = getConfig().getLong("EnderPearl.Distance", 0);
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
        nowInCombatMsg = getConfig().isConfigurationSection("Messages.NowInCombat")
            ? getConfig().getString("Messages.NowInCombat.text", "")
            : getConfig().getString("Messages.NowInCombat", "");
        noLongerInCombatMsg = getConfig().isConfigurationSection("Messages.NoLongerInCombat")
            ? getConfig().getString("Messages.NoLongerInCombat.text", "")
            : getConfig().getString("Messages.NoLongerInCombat", "");

        List<String> ignoredList = getConfig().getStringList("ignored-projectiles");
        for (String s : ignoredList) {
            ignoredProjectiles.add(s.toUpperCase());
        }
    }

    private void initializeManagers() {
        superVanishManager = new SuperVanishManager();
        crystalManager = new CrystalManager();
        
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            worldGuardUtil = new WorldGuardUtil();
        }
        
        if (glowingEnabled) {
            glowManager = new GlowManager();
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

    private void registerListeners() {
        playerMoveListener = new PlayerMoveListener();
        Bukkit.getPluginManager().registerEvents(new PlayerCommandPreprocessListener(), this);
        Bukkit.getPluginManager().registerEvents(playerMoveListener, this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerTeleportListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(), this);
        Bukkit.getPluginManager().registerEvents(new CustomDeathMessageListener(), this);
        Bukkit.getPluginManager().registerEvents(new SelfCombatListener(), this);
        Bukkit.getPluginManager().registerEvents(this, this);

        endCrystalListener = new EndCrystalListener();
        Bukkit.getPluginManager().registerEvents(endCrystalListener, this);
        Bukkit.getPluginManager().registerEvents(new EntityPlaceListener(), this);
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
    }
    
    private void initializeAPI() {
        MasterCombatAPIProvider.set(new MasterCombatAPIBackend(this));
        Bukkit.getPluginManager().callEvent(new MasterCombatLoadEvent());
    }

    @Override
    public void onDisable() {
        net.opmasterleo.combat.util.SchedulerUtil.setShuttingDown(true);
        net.opmasterleo.combat.manager.Update.setShuttingDown(true);
        net.opmasterleo.combat.manager.Update.cleanupTasks();
        
        if (glowManager != null) {
            glowManager.cleanup();
        }

        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}
        net.opmasterleo.combat.util.SchedulerUtil.cancelAllTasks(this);

        combatPlayers.clear();
        combatOpponents.clear();
        lastActionBarSeconds.clear();
        
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
            boolean playerInProtectedRegion = worldGuardUtil.isPvpDenied(player);
            boolean opponentInProtectedRegion = !playerUUID.equals(opponentUUID) && worldGuardUtil.isPvpDenied(opponent);
            
            if (playerInProtectedRegion || opponentInProtectedRegion) {
                return;
            }
        }

        long expiry = System.currentTimeMillis() + (getConfig().getLong("Duration", 0) * 1000L);
        
        boolean playerWasInCombat = combatPlayers.containsKey(playerUUID);
        boolean opponentWasInCombat = !playerUUID.equals(opponentUUID) && combatPlayers.containsKey(opponentUUID);
        
        combatOpponents.put(playerUUID, opponentUUID);
        combatPlayers.put(playerUUID, expiry);
        
        if (!playerUUID.equals(opponentUUID)) {
            combatOpponents.put(opponentUUID, playerUUID);
            combatPlayers.put(opponentUUID, expiry);
        }
        
        if (!playerWasInCombat && nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
            player.sendMessage(prefix + nowInCombatMsg);
        }
        
        if (!playerUUID.equals(opponentUUID) && !opponentWasInCombat && nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
            opponent.sendMessage(prefix + nowInCombatMsg);
        }

        if (glowingEnabled && getConfig().getBoolean("General.CombatTagGlowing", false) && glowManager != null) {
            if (!playerWasInCombat) glowManager.setGlowing(player, true);
            if (!playerUUID.equals(opponentUUID) && !opponentWasInCombat) glowManager.setGlowing(opponent, true);
        }
    }
    
    public void forceCombatCleanup(UUID playerUUID) {
        if (playerUUID == null) return;
        combatPlayers.remove(playerUUID);
        UUID opponentUUID = combatOpponents.remove(playerUUID);
        lastActionBarSeconds.remove(playerUUID);
        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null && glowingEnabled && glowManager != null) {
            glowManager.setGlowing(player, false);
        }

        if (opponentUUID != null) {
            UUID currentOpponentRef = combatOpponents.get(opponentUUID);
            if (currentOpponentRef != null && currentOpponentRef.equals(playerUUID)) {
                combatOpponents.remove(opponentUUID);
            }
            
            Player opponent = Bukkit.getPlayer(opponentUUID);
            if (opponent != null && glowingEnabled && glowManager != null) {
                if (!combatPlayers.containsKey(opponentUUID)) {
                    glowManager.setGlowing(opponent, false);
                }
            }
        }
    }

    private void startCombatTimer() {
        final long timerInterval = isPacketEventsAvailable() ? 20L : Math.min(40L, getDynamicInterval());
        final int MAX_BATCH_SIZE = 2000;
        final int MIN_BATCH_SIZE = 50;
        final int OPTIMAL_BATCH_SIZE = Math.min(MAX_BATCH_SIZE, Math.max(MIN_BATCH_SIZE, Bukkit.getMaxPlayers() / 8));
        final UUID[] processBuffer = new UUID[OPTIMAL_BATCH_SIZE];
        final int[] tickCounter = {0};
        final int[] skippedTicks = {0};
        
        Runnable timerTask = () -> {
            if (skippedTicks[0] > 0) {
                skippedTicks[0]--;
                return;
            }
            
            long startTime = System.nanoTime();
            long currentTime = System.currentTimeMillis();
            int count = 0;
            UUID[] playerKeys = combatPlayers.keySet().toArray(new UUID[0]);
            
            for (int i = 0; i < playerKeys.length && count < OPTIMAL_BATCH_SIZE; i++) {
                processBuffer[count++] = playerKeys[i];
            }
            
            for (int i = 0; i < count; i++) {
                UUID uuid = processBuffer[i];
                if (uuid == null) continue;
                Long endTime = combatPlayers.get(uuid);
                if (endTime == null) continue;
                Player player = Bukkit.getPlayer(uuid);
                if (player == null) {
                    combatPlayers.remove(uuid);
                    combatOpponents.remove(uuid);
                    lastActionBarSeconds.remove(uuid);
                    continue;
                }

                if (currentTime >= endTime) {
                    handleCombatEnd(player);
                    lastActionBarSeconds.remove(uuid);
                } else {
                    Long lastUpdate = lastActionBarSeconds.get(uuid);
                    if (lastUpdate == null || currentTime - lastUpdate >= 500) {
                        updateActionBar(player, endTime, currentTime);
                        lastActionBarSeconds.put(uuid, currentTime);
                    }
                }

                if (i % 25 == 0 && i > 0) {
                    if ((System.nanoTime() - startTime) > 5_000_000) {
                        Thread.yield();
                    }
                }
            }

            long elapsed = System.nanoTime() - startTime;
            tickCounter[0]++;
            if (tickCounter[0] >= 20) {
                tickCounter[0] = 0;
                if (elapsed > 25_000_000) {
                    skippedTicks[0] = 2;
                }
            }
        };

        try {
            SchedulerUtil.runTaskTimerAsync(this, timerTask, timerInterval, timerInterval);
        } catch (Exception e) {
            getLogger().warning("Failed to schedule combat timer: " + e.getMessage());
        }
    }
    
    private long getDynamicInterval() {
        int playerCount = Bukkit.getOnlinePlayers().size();
        double tps;
        try {
            tps = Bukkit.getServer().getTPS()[0];
        } catch (Throwable ignored) {
            tps = 20.0;
        }

        long interval = tps >= 19.8 ? 10L : 
                        tps >= 19.0 ? 12L : 
                        tps >= 18.0 ? 15L : 
                        tps >= 16.0 ? 20L : 25L;

        if (playerCount > 5000) interval = (long)(interval * 2.0);
        else if (playerCount > 2000) interval = (long)(interval * 1.5);
        else if (playerCount > 1000) interval = (long)(interval * 1.2);
        
        return interval;
    }

    private void handleCombatEnd(Player player) {
        UUID playerUUID = player.getUniqueId();
        combatPlayers.remove(playerUUID);
        UUID opponentUUID = combatOpponents.remove(playerUUID);
        
        if (glowingEnabled && glowManager != null) {
            glowManager.setGlowing(player, false);
            if (opponentUUID != null) {
                Player opponent = Bukkit.getPlayer(opponentUUID);
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
        net.kyori.adventure.text.Component component = net.opmasterleo.combat.util.ChatUtil.parse(prefix + message);
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
        long seconds = (endTime - currentTime + 999) / 1000;
        String format = getConfig().getString("General.Format");
        if (format == null || format.isEmpty()) return;

        String message = format.replace("%seconds%", String.valueOf(seconds));
        net.kyori.adventure.text.Component component = net.opmasterleo.combat.util.ChatUtil.parse(message);
        player.sendActionBar(component);
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
        Long until = combatPlayers.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
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
    
    public boolean canDamage(Player attacker, Player victim) {
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR ||
            victim.getGameMode() == GameMode.CREATIVE || victim.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        if (attacker.equals(victim) && !getConfig().getBoolean("self-combat", false)) {
            return false;
        }

        if (worldGuardUtil != null) {
            if (worldGuardUtil.isPvpDenied(attacker) || worldGuardUtil.isPvpDenied(victim)) {
                return false;
            }
        }

        if (newbieProtectionListener != null) {
            boolean attackerProtected = newbieProtectionListener.isActuallyProtected(attacker);
            boolean victimProtected = newbieProtectionListener.isActuallyProtected(victim);

            if (attackerProtected && !victimProtected) return false;
            if (!attackerProtected && victimProtected) return false;
        }

        if (superVanishManager != null && 
            (superVanishManager.isVanished(attacker) || superVanishManager.isVanished(victim))) {
            return false;
        }

        return true;
    }

    public Player getCombatOpponent(Player player) {
        UUID opponentUUID = combatOpponents.get(player.getUniqueId());
        if (opponentUUID == null) {
            return null;
        }
        return Bukkit.getPlayer(opponentUUID);
    }

    public void keepPlayerInCombat(Player player) {
        if (player != null) {
            combatPlayers.put(player.getUniqueId(), 
                System.currentTimeMillis() + 1000 * getConfig().getLong("Duration", 0));
        }
    }

    public String getMessage(String key) {
        String message = getConfig().getString(key, "");
        if (message == null) message = "";
        return message;
    }

    public void reloadCombatConfig() {
        reloadConfig();
        ConfigUtil.updateConfig(this);
        loadConfigValues();
        
        if (newbieProtectionListener != null) {
            newbieProtectionListener.reloadConfig();
        }
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

    public ConcurrentHashMap<UUID, Long> getCombatPlayers() {
        return combatPlayers;
    }

    public ConcurrentHashMap<UUID, UUID> getCombatOpponents() {
        return combatOpponents;
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

    private boolean isPacketEventsAvailable() {
        return Bukkit.getPluginManager().getPlugin("PacketEvents") != null;
    }

    public RespawnAnchorListener getRespawnAnchorListener() {
        return respawnAnchorListener;
    }

    public BedExplosionListener getBedExplosionListener() {
        return bedExplosionListener;
    }

    public void forceSetCombat(Player player, Player opponent) {
        if (!combatEnabled || player == null || !isCombatEnabledInWorld(player) || shouldBypass(player)) return;
        if (worldGuardUtil != null) {
            boolean playerInProtectedRegion = worldGuardUtil.isPvpDenied(player);
            boolean opponentInProtectedRegion = opponent != null &&
                                                !player.equals(opponent) &&
                                                worldGuardUtil.isPvpDenied(opponent);

            if (playerInProtectedRegion || opponentInProtectedRegion) {
                return;
            }
        }

        long expiry = System.currentTimeMillis() + (getConfig().getLong("General.duration", 0) * 1000L);
        if (player != null) {
            UUID playerUUID = player.getUniqueId();
            combatOpponents.put(playerUUID, opponent != null ? opponent.getUniqueId() : null);
            combatPlayers.put(playerUUID, expiry);
            boolean wasInCombat = combatPlayers.containsKey(playerUUID) && combatPlayers.get(playerUUID) > System.currentTimeMillis();
            if (!wasInCombat && nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
                player.sendMessage(prefix + nowInCombatMsg);
            }

            if (glowingEnabled) {
                if (!wasInCombat && player.isGliding()) player.setGliding(false);
                if (!wasInCombat && player.isFlying()) {
                    player.setFlying(false);
                    player.setAllowFlight(false);
                }
                
                if (glowManager != null) {
                    glowManager.setGlowing(player, true);
                }
            }
            
            lastActionBarSeconds.put(playerUUID, System.currentTimeMillis());
        }
        
        if (opponent != null && !opponent.equals(player)) {
            UUID opponentUUID = opponent.getUniqueId();
            combatOpponents.put(opponentUUID, player.getUniqueId());
            combatPlayers.put(opponentUUID, expiry);
            boolean wasInCombat = combatPlayers.containsKey(opponentUUID) && combatPlayers.get(opponentUUID) > System.currentTimeMillis();
            if (!wasInCombat && nowInCombatMsg != null && !nowInCombatMsg.isEmpty()) {
                opponent.sendMessage(prefix + nowInCombatMsg);
            }

            if (glowingEnabled && glowManager != null) {
                glowManager.setGlowing(opponent, true);
            }
            
            lastActionBarSeconds.put(opponentUUID, System.currentTimeMillis());
        }
    }

    public void handlePacketEvent(Player player, Player opponent) {
    }

    @Deprecated
    public org.bukkit.plugin.PluginDescriptionFile getPluginDescription() {
        return super.getDescription();
    }

    private void sendStartupMessage() {
        String version = getPluginMeta().getVersion();
        String pluginName = getPluginMeta().getDisplayName();

        String apiType;
        String serverJarName;
        boolean isFolia = Update.isFolia();

        try {
            String serverName = Bukkit.getServer().getName();
            serverJarName = serverName;
            if (isFolia) {
                apiType = "folia";
            } else {
                apiType = "bukkit";
            }
        } catch (Exception e) {
            apiType = isFolia ? "folia" : "bukkit";
            serverJarName = isFolia ? "Folia" : "Unknown";
        }

        boolean worldGuardDetected = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
        if (worldGuardDetected) {
            Bukkit.getConsoleSender().sendMessage("§cINFO §8» §aWorldGuard loaded!");
        } else {
            Bukkit.getConsoleSender().sendMessage("§cINFO §8» §aWorldGuard not loaded!");
        }

        boolean packetEventsLoaded = Bukkit.getPluginManager().getPlugin("PacketEvents") != null;
        if (packetEventsLoaded) {
            Bukkit.getConsoleSender().sendMessage("§cINFO §8» §aPacketEvents loaded!");
        } else {
            Bukkit.getConsoleSender().sendMessage("§cINFO §8» §cPacketEvents not loaded!!");
        }

        String displayText;
        if (pluginName.contains(version)) {
            displayText = pluginName;
        } else {
            displayText = pluginName + " - v" + version;
        }

        String asciiArt =
            "&b   ____                _           _               \n" +
            "&b  / ___|___  _ __ ___ | |__   __ _| |_             \n" +
            "&b | |   / _ \\| '_ ` _ \\| '_ \\ / _` | __|   " + displayText + "\n" +
            "&b | |__| (_) | | | | | | |_) | (_| | |_    Currently using " + apiType + " - " + serverJarName + "\n" +
            "&b  \\____\\___/|_| |_| |_|_.__/ \\__,_|\\__|   \n";

        for (String line : asciiArt.split("\n")) {
            Bukkit.getConsoleSender().sendMessage(net.opmasterleo.combat.util.ChatUtil.parse(line));
        }
    }
    public void updateGlowing() {
        if (glowingEnabled && glowManager != null) {
            glowManager.updateGlowingForAll();
        }
    }

    private final EntityManager entityManager = new EntityManager();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUtil.updateConfig(this);
        instance = this;

        loadConfigValues();
        initializeManagers();
        registerCommands();
        registerListeners();
        startCombatTimer();
        initializeAPI();
        sendStartupMessage();

        int pluginId = 25701;
        new Metrics(this, pluginId);

        if (getConfig().getBoolean("link-bed-explosions", true)) {
            bedExplosionListener = new BedExplosionListener();
            Bukkit.getPluginManager().registerEvents(bedExplosionListener, this);
        } else {
            bedExplosionListener = null;
        }
    }

    public EntityManager getEntityManager() {
        return entityManager;
    }
}