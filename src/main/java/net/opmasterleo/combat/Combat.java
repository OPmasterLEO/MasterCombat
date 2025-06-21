package net.opmasterleo.combat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.opmasterleo.combat.api.MasterCombatAPIBackend;
import net.opmasterleo.combat.api.MasterCombatAPIProvider;
import net.opmasterleo.combat.api.events.MasterCombatLoadEvent;
import net.opmasterleo.combat.command.CombatCommand;
import net.opmasterleo.combat.listener.CustomDeathMessageListener;
import net.opmasterleo.combat.listener.EndCrystalListener;
import net.opmasterleo.combat.listener.EntityDamageByEntityListener;
import net.opmasterleo.combat.listener.EntityPlaceListener;
import net.opmasterleo.combat.listener.PlayerCommandPreprocessListener;
import net.opmasterleo.combat.listener.PlayerDeathListener;
import net.opmasterleo.combat.listener.PlayerMoveListener;
import net.opmasterleo.combat.listener.PlayerQuitListener;
import net.opmasterleo.combat.listener.PlayerTeleportListener;
import net.opmasterleo.combat.listener.SelfCombatListener;
import net.opmasterleo.combat.listener.NewbieProtectionListener;
import net.opmasterleo.combat.manager.CrystalManager;
import net.opmasterleo.combat.manager.Update;
import net.opmasterleo.combat.manager.WorldGuardUtil;

public class Combat extends JavaPlugin implements Listener {

    private static Combat instance;
    private final ConcurrentHashMap<UUID, Long> combatPlayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> combatOpponents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastActionBarSeconds = new ConcurrentHashMap<>();
    private boolean enableWorldsEnabled;
    private List<String> enabledWorlds;
    private boolean combatEnabled;
    private boolean glowingEnabled;
    private WorldGuardUtil worldGuardUtil;
    private PlayerMoveListener playerMoveListener;
    private EndCrystalListener endCrystalListener;
    private CrystalManager crystalManager;

    private boolean disableElytra;
    private boolean enderPearlEnabled;
    private long enderPearlDistance;
    private String elytraDisabledMsg;
    private final Set<String> ignoredProjectiles = ConcurrentHashMap.newKeySet();

    private NewbieProtectionListener newbieProtectionListener;

    @Override
    @SuppressWarnings("deprecation")
    public void onEnable() {
        saveDefaultConfig();
        instance = this;

        combatEnabled = getConfig().getBoolean("combat-enabled", true);
        glowingEnabled = getConfig().getBoolean("CombatTagGlowing.Enabled", false);

        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            worldGuardUtil = new WorldGuardUtil();
        }

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            Bukkit.getConsoleSender().sendMessage("§a[MasterCombat] ProtocolLib detected and integrated.");
        } else {
            Bukkit.getConsoleSender().sendMessage("§c[MasterCombat] ProtocolLib not detected. Some features may not work.");
        }

        registerListeners();
        getCommand("combat").setExecutor(new CombatCommand());
        startCombatTimer();
        sendStartupMessage();
        Update.checkForUpdates(this);
        Update.notifyOnServerOnline(this);

        int pluginId = 25701;
        @SuppressWarnings("unused")
        Metrics metrics = new Metrics(this, pluginId);

        endCrystalListener = new EndCrystalListener();
        Bukkit.getPluginManager().registerEvents(endCrystalListener, this);
        Bukkit.getPluginManager().registerEvents(new EntityPlaceListener(), this);

        crystalManager = new CrystalManager();

        newbieProtectionListener = new NewbieProtectionListener();
        Bukkit.getPluginManager().registerEvents(newbieProtectionListener, this);

        MasterCombatAPIProvider.register(new MasterCombatAPIBackend(this));
        Bukkit.getPluginManager().callEvent(new MasterCombatLoadEvent());
    }

    @Override
    public void onDisable() {
        combatPlayers.clear();
        combatOpponents.clear();
        lastActionBarSeconds.clear();
        if (glowingEnabled) {
            for (UUID uuid : combatPlayers.keySet()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) player.setGlowing(false);
            }
        }
        Bukkit.getConsoleSender().sendMessage("§cMasterCombat plugin has been disabled.");
    }

    private void registerListeners() {
        playerMoveListener = new PlayerMoveListener();
        Bukkit.getPluginManager().registerEvents(new EntityDamageByEntityListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerCommandPreprocessListener(), this);
        Bukkit.getPluginManager().registerEvents(playerMoveListener, this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerTeleportListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(), this);
        Bukkit.getPluginManager().registerEvents(new CustomDeathMessageListener(), this);
        Bukkit.getPluginManager().registerEvents(new SelfCombatListener(), this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.isOp() && getConfig().getBoolean("update-notify-chat", false)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                String pluginName = getDescription().getName();
                String currentVersion = getDescription().getVersion();
                String latestVersion = Update.getLatestVersion();

                Bukkit.getScheduler().runTask(this, () -> {
                    if (latestVersion == null) {
                        player.sendMessage("§c[" + pluginName + "]» Unable to fetch update information.");
                        return;
                    }
                    if (currentVersion.equalsIgnoreCase(latestVersion)) {
                        player.sendMessage("§a[" + pluginName + "]» This server is running the latest " + pluginName + " version.");
                    } else {
                        player.sendMessage("§e[" + pluginName + "]» This server is running " + pluginName + " version " + currentVersion +
                                " but the latest is " + latestVersion + ".");
                    }
                });
            });
        }
    }

    private void startCombatTimer() {
        Runnable timerTask = () -> {
            long currentTime = System.currentTimeMillis();
            combatPlayers.forEach((uuid, endTime) -> {
                Player player = Bukkit.getPlayer(uuid);

                if (player == null) {
                    combatPlayers.remove(uuid);
                    combatOpponents.remove(uuid);
                    lastActionBarSeconds.remove(uuid);
                    if (glowingEnabled) setPlayerGlowing(uuid, false);
                    return;
                }

                if (currentTime >= endTime) {
                    handleCombatEnd(player);
                    lastActionBarSeconds.remove(uuid);
                    if (glowingEnabled) setPlayerGlowing(uuid, false);
                } else {
                    updateActionBar(player, endTime, currentTime);
                    if (glowingEnabled) setPlayerGlowing(uuid, true);
                }
            });
        };

        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                timerTask.run();
            }, 20L, getDynamicInterval());
        } catch (ClassNotFoundException e) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, timerTask, 20L, getDynamicInterval());
        }
    }

    private long getDynamicInterval() {
        double tps = 20.0;
        try {
            tps = Bukkit.getServer().getTPS()[0];
        } catch (Throwable ignored) {}
        if (tps >= 19.5) return 20L;
        if (tps >= 18.0) return 30L;
        if (tps >= 16.0) return 40L;
        return 60L;
    }

    private void setPlayerGlowing(UUID uuid, boolean glowing) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline() && player.isGlowing() != glowing) {
            player.setGlowing(glowing);
        }
    }

    private void handleCombatEnd(Player player) {
        UUID playerUUID = player.getUniqueId();
        combatPlayers.remove(playerUUID);
        UUID opponentUUID = combatOpponents.remove(playerUUID);
        if (opponentUUID != null) {
            combatOpponents.remove(opponentUUID);
            if (glowingEnabled) setPlayerGlowing(opponentUUID, false);
        }
        if (glowingEnabled) setPlayerGlowing(playerUUID, false);
        sendCombatEndMessage(player);
    }

    private void updateCombatState(Player player, Player opponent, long duration) {
        combatPlayers.put(player.getUniqueId(), duration);
        if (opponent != null) {
            combatPlayers.put(opponent.getUniqueId(), duration);
            combatOpponents.put(player.getUniqueId(), opponent.getUniqueId());
            combatOpponents.put(opponent.getUniqueId(), player.getUniqueId());
        }
    }

    private void sendCombatEndMessage(Player player) {
        String noLongerInCombatMsg = getConfig().getString("Messages.NoLongerInCombat", "");
        if (noLongerInCombatMsg != null && !noLongerInCombatMsg.isEmpty()) {
            player.sendMessage(getMessage("Messages.Prefix") + getMessage("Messages.NoLongerInCombat"));
        }
    }

    @SuppressWarnings("deprecation")
    private void updateActionBar(Player player, long endTime, long currentTime) {
        long seconds = (endTime - currentTime + 999) / 1000;
        player.sendActionBar(getMessage("ActionBar.Format").replace("%seconds%", String.valueOf(seconds)));
    }

    public boolean isCombatEnabledInWorld(Player player) {
        return enabledWorlds == null || !enableWorldsEnabled || enabledWorlds.contains(player.getWorld().getName());
    }

    public boolean isInCombat(Player player) {
        Long until = combatPlayers.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public void setCombat(Player player, Player opponent) {
        // Hook: Prevent combat tagging if either player is protected by NewbieProtection
        NewbieProtectionListener protection = getNewbieProtectionListener();
        if (protection != null) {
            if ((player != null && protection.isProtected(player)) || (opponent != null && protection.isProtected(opponent))) {
                return;
            }
        }
        if (!combatEnabled || !isCombatEnabledInWorld(player)) return;
        if (shouldBypass(player)) return;

        long duration = System.currentTimeMillis() + 1000 * getConfig().getLong("Duration", 0);
        if (player == null) return;
        Long current = combatPlayers.get(player.getUniqueId());
        if (current != null && current >= duration) return;
        updateCombatState(player, opponent, duration);
        sendCombatStartMessage(player);
        restrictMovement(player);
        setPlayerGlowingIfNeeded(player.getUniqueId(), true);
        if (glowingEnabled && opponent != null) setPlayerGlowingIfNeeded(opponent.getUniqueId(), true);
    }

    private void setPlayerGlowingIfNeeded(UUID uuid, boolean glowing) {
        if (!glowingEnabled) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline() && player.isGlowing() != glowing) {
            player.setGlowing(glowing);
        }
    }

    private boolean shouldBypass(Player player) {
        return (getConfig().getBoolean("ignore-op", true) && player.isOp()) 
            || player.getGameMode() == GameMode.CREATIVE 
            || player.getGameMode() == GameMode.SPECTATOR;
    }

    private void sendCombatStartMessage(Player player) {
        if (!isInCombat(player) && !getConfig().getString("Messages.NowInCombat", "").isEmpty()) {
            player.sendMessage(getMessage("Messages.Prefix") + getMessage("Messages.NowInCombat"));
        }
    }

    private void restrictMovement(Player player) {
        player.setGliding(false);
        player.setFlying(false);
        player.setAllowFlight(false);
    }

    public Player getCombatOpponent(Player player) {
        UUID opponentUUID = combatOpponents.get(player.getUniqueId());
        if (opponentUUID == null) {
            return null;
        }
        return Bukkit.getPlayer(opponentUUID);
    }

    public void keepPlayerInCombat(Player player) {
        if (isInCombat(player)) {
            combatPlayers.put(player.getUniqueId(), 
                System.currentTimeMillis() + 1000 * getConfig().getLong("Duration", 0));
        }
    }

    public String getMessage(String key) {
        String message = getConfig().getString(key, "");
        if (message == null) message = "";
        return LegacyComponentSerializer.legacySection().serialize(
            LegacyComponentSerializer.legacy('&').deserialize(message)
        );
    }

    public void reloadCombatConfig() {
        reloadConfig();
        combatEnabled = getConfig().getBoolean("combat-enabled", true);
        enableWorldsEnabled = getConfig().getBoolean("EnabledWorlds.enabled", false);
        enabledWorlds = getConfig().getStringList("EnabledWorlds.worlds");
        if (enabledWorlds == null || enabledWorlds.isEmpty()) {
            enabledWorlds = List.of("world");
        }
        glowingEnabled = getConfig().getBoolean("CombatTagGlowing.Enabled", false);

        disableElytra = getConfig().getBoolean("disable-elytra", false);
        enderPearlEnabled = getConfig().getBoolean("EnderPearl.Enabled", false);
        enderPearlDistance = getConfig().getLong("EnderPearl.Distance", 0);
        elytraDisabledMsg = getMessage("Messages.ElytraDisabled");
        if (elytraDisabledMsg == null || elytraDisabledMsg.isEmpty()) {
            elytraDisabledMsg = "§cElytra usage is disabled while in combat.";
        }
        ignoredProjectiles.clear();
        List<String> ignoredList = getConfig().getStringList("ignored-projectiles");
        for (String s : ignoredList) ignoredProjectiles.add(s.toUpperCase());

        if (!glowingEnabled) {
            for (UUID uuid : combatPlayers.keySet()) {
                setPlayerGlowing(uuid, false);
            }
        }
    }

    public boolean isDisableElytra() { return disableElytra; }
    public boolean isEnderPearlEnabled() { return enderPearlEnabled; }
    public long getEnderPearlDistance() { return enderPearlDistance; }
    public String getElytraDisabledMsg() { return elytraDisabledMsg; }
    public Set<String> getIgnoredProjectiles() { return ignoredProjectiles; }

    public void setCombatEnabled(boolean enabled) {
        this.combatEnabled = enabled;
    }

    public static Combat getInstance() {
        return instance;
    }

    @SuppressWarnings("deprecation")
    private void sendStartupMessage() {
        String version = getDescription().getVersion();
        String pluginName = getDescription().getName();

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

        String asciiArt =
            "&b   ____                _           _               \n" +
            "&b  / ___|___  _ __ ___ | |__   __ _| |_             \n" +
            "&b | |   / _ \\| '_ ` _ \\| '_ \\ / _` | __|   " + pluginName + " v" + version + "\n" +
            "&b | |__| (_) | | | | | | |_) | (_| | |_    Currently using " + apiType + " - " + serverJarName + "\n" +
            "&b  \\____\\___/|_| |_| |_|_.__/ \\__,_|\\__|   \n";

        for (String line : asciiArt.split("\n")) {
            Bukkit.getConsoleSender().sendMessage(LegacyComponentSerializer.legacy('&').deserialize(line));
        }
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

    public boolean isCombatEnabled() {
        return combatEnabled;
    }
}