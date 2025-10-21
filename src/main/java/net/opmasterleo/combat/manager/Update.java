package net.opmasterleo.combat.manager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.SchedulerUtil;

public class Update {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/OPmasterLEO/MasterCombat/releases/latest";
    private static String latestVersion;
    private static String downloadUrl;
    private static volatile boolean updateCheckInProgress = false;
    private static volatile boolean updateDownloadInProgress = false;
    private static volatile boolean isShuttingDown = false;
    private static final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
    private static final Set<BukkitTask> updateTasks = ConcurrentHashMap.newKeySet();
    private static boolean updateFound = false;
    private static long lastCheckTime = 0;
    private static final long CHECK_CACHE_DURATION = 300000;
    private static final ThreadLocal<StringBuilder> stringBuilderCache = 
        ThreadLocal.withInitial(() -> new StringBuilder(256));

    public static void setShuttingDown(boolean shuttingDown) {
        isShuttingDown = shuttingDown;
        if (shuttingDown) {
            activeConnections.forEach(conn -> {
                try { conn.disconnect(); } catch (Exception ignored) {}
            });
            activeConnections.clear();
            updateTasks.forEach(task -> {
                try { if (!task.isCancelled()) task.cancel(); } catch (Exception ignored) {}
            });
            updateTasks.clear();
        }
    }

    public static String getLatestVersion() {
        if (latestVersion != null && (System.currentTimeMillis() - lastCheckTime) < CHECK_CACHE_DURATION) {
            return latestVersion;
        }
        
        if (latestVersion == null && !updateCheckInProgress && !isShuttingDown) {
            updateCheckInProgress = true;
            try {
                performUpdateCheck(Bukkit.getPluginManager().getPlugin("MasterCombat"));
            } finally {
                updateCheckInProgress = false;
            }
        }
        return latestVersion;
    }

    public static void checkForUpdates(Plugin plugin, CommandSender sender) {
        if (isShuttingDown) return;
        if (updateCheckInProgress || (System.currentTimeMillis() - lastCheckTime) < CHECK_CACHE_DURATION) {
            if (latestVersion != null && sender != null) {
                String currentVersion = plugin.getPluginMeta().getVersion();
                String pluginName = plugin.getName();
                String prefix = plugin.getConfig().getString("Messages.Prefix", "");
                handleVersionNotification(sender, pluginName, currentVersion, prefix);
            }
            return;
        }

        updateCheckInProgress = true;
        sendMessage(sender, "§b[MasterCombat] §eChecking for updates…");

        BukkitTask task = SchedulerUtil.runTaskAsync(plugin, () -> {
            try {
                performUpdateCheck(plugin);
                SchedulerUtil.runTask(plugin, () -> {
                    if (!isShuttingDown && sender != null) {
                        String currentVersion = plugin.getPluginMeta().getVersion();
                        String pluginName = plugin.getName();
                        String prefix = plugin.getConfig().getString("Messages.Prefix", "");
                        handleVersionNotification(sender, pluginName, currentVersion, prefix);
                    }
                });
            } finally {
                updateCheckInProgress = false;
            }
        });
        if (task != null) updateTasks.add(task);
    }

    public static void checkForUpdates(Plugin plugin) {
        checkForUpdates(plugin, null);
    }

    public static void notifyOnServerOnline(Plugin plugin) {
        if (isShuttingDown) return;
        final String prefix = plugin.getConfig().getString("Messages.Prefix", "");
        BukkitTask task = SchedulerUtil.runTaskLater(plugin, () -> {
            String pluginName = plugin.getName();
            String currentVersion = plugin.getPluginMeta().getVersion();
            if (latestVersion == null && !updateCheckInProgress && !isShuttingDown 
                && (System.currentTimeMillis() - lastCheckTime) >= CHECK_CACHE_DURATION) {
                updateCheckInProgress = true;
                performUpdateCheck(plugin);
                updateCheckInProgress = false;
            }
            handleVersionNotification(null, pluginName, currentVersion, prefix);
        }, 20L * 3);
        if (task != null) updateTasks.add(task);
    }

    public static void notifyOnPlayerJoin(Player player, Plugin plugin) {
        if (isShuttingDown || !player.isOp() || !plugin.getConfig().getBoolean("update-notify-chat", false)) return;
        if (latestVersion != null && (System.currentTimeMillis() - lastCheckTime) < CHECK_CACHE_DURATION) {
            String pluginName = plugin.getName();
            String currentVersion = plugin.getPluginMeta().getVersion();
            String prefix = plugin.getConfig().getString("Messages.Prefix", "");
            handleVersionNotification(player, pluginName, currentVersion, prefix);
            return;
        }
        
        String pluginName = plugin.getName();
        String currentVersion = plugin.getPluginMeta().getVersion();
        final String prefix = plugin.getConfig().getString("Messages.Prefix", "");
        
        if (latestVersion == null && !updateCheckInProgress) {
            updateCheckInProgress = true;
            BukkitTask task = SchedulerUtil.runTaskAsync(plugin, () -> {
                try {
                    performUpdateCheck(plugin);
                } finally {
                    updateCheckInProgress = false;
                    SchedulerUtil.runTask(plugin, () -> {
                        if (!isShuttingDown) {
                            handleVersionNotification(player, pluginName, currentVersion, prefix);
                        }
                    });
                }
            });
            if (task != null) updateTasks.add(task);
        } else {
            handleVersionNotification(player, pluginName, currentVersion, prefix);
        }
    }
    
    private static void handleVersionNotification(CommandSender sender, String pluginName, String currentVersion, String prefix) {
        CommandSender target = (sender != null) ? sender : Bukkit.getConsoleSender();
        if (latestVersion == null) {
            StringBuilder sb = stringBuilderCache.get();
            sb.setLength(0);
            sb.append(prefix).append("§c[").append(pluginName).append("]» Unable to fetch update information.");
            sendMessage(target, sb.toString());
            return;
        }
        
        String normalizedCurrent = normalizeVersion(currentVersion);
        String normalizedLatest = normalizeVersion(latestVersion);
        int comparison = compareVersions(normalizedCurrent, normalizedLatest);
        int behind = getVersionsBehind(normalizedCurrent, normalizedLatest);
        StringBuilder sb = stringBuilderCache.get();
        
        if (behind > 0 && comparison < 0) {
            sb.setLength(0);
            sb.append(prefix).append("You are ").append(behind).append(" version")
              .append(behind == 1 ? "" : "s").append(" behind, update using §6/combat update");
            sendMessage(target, sb.toString());
        }

        if (comparison == 0) {
            sb.setLength(0);
            sb.append("§a[").append(pluginName).append("]» Running latest version §7(")
              .append(currentVersion.replaceFirst("^v", "")).append(")");
            sendMessage(target, sb.toString());
        } else if (comparison < 0) {
            sb.setLength(0);
            sb.append("§e[").append(pluginName).append("]» Update required! §7(Installed: ")
              .append(currentVersion.replaceFirst("^v", "")).append(", Latest: ")
              .append(latestVersion.replaceFirst("^v", "")).append(")");
            sendMessage(target, sb.toString());
            sendMessage(target, "§eUse §6/combat update §eto install the update");
        } else {
            sb.setLength(0);
            sb.append("§a[").append(pluginName).append("]» Development build detected §7(")
              .append(currentVersion.replaceFirst("^v", "")).append(")");
            sendMessage(target, sb.toString());
            
            sb.setLength(0);
            sb.append("§aLatest public version: §7").append(latestVersion.replaceFirst("^v", ""));
            sendMessage(target, sb.toString());
        }
    }

    public static void downloadAndReplaceJar(Plugin plugin, CommandSender sender) {
        if (isShuttingDown || updateDownloadInProgress) return;
        updateDownloadInProgress = true;
        
        BukkitTask task = SchedulerUtil.runTaskAsync(plugin, () -> {
            try {
                String currentVersion = plugin.getPluginMeta().getVersion();
                String normalizedCurrent = normalizeVersion(currentVersion);
                
                if (latestVersion == null) {
                    performUpdateCheck(plugin);
                }
                
                if (latestVersion == null) {
                    sendMessage(sender, "§cUpdate failed: Could not fetch version information");
                    return;
                }
                
                String normalizedLatest = normalizeVersion(latestVersion);
                int comparison = compareVersions(normalizedCurrent, normalizedLatest);
                
                if (comparison > 0) {
                    sendMessage(sender, "§aYou're running a development build §7(v" + currentVersion + ")");
                    sendMessage(sender, "§aUpdate skipped to prevent downgrading");
                    return;
                }
                
                if (comparison == 0) {
                    sendMessage(sender, "§aYou're already on the latest version §7(v" + currentVersion + ")");
                    return;
                }
                
                sendMessage(sender, "§b[MasterCombat] §eDownloading update...");
                performJarReplacement(plugin, sender);
            } catch (Exception e) {
                if (!isShuttingDown) {
                    sendMessage(sender, "§cUpdate error: " + e.getMessage());
                }
            } finally {
                updateDownloadInProgress = false;
            }
        });
        if (task != null) updateTasks.add(task);
    }

    public static void downloadAndReplaceJar(Plugin plugin) {
        downloadAndReplaceJar(plugin, null);
    }

    public static boolean isUpdateFound() {
        return updateFound;
    }

    public static void setUpdateFound(boolean found) {
        updateFound = found;
    }

    private static void performUpdateCheck(Plugin plugin) {
        if (isShuttingDown) return;
        String pluginNameForDebug = (plugin != null) ? plugin.getName() : "MasterCombat";
        if (!pluginNameForDebug.isEmpty() && isShuttingDown) return;
        
        try {
            URL url = URI.create(GITHUB_API_URL).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            activeConnections.add(connection);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setRequestProperty("User-Agent", "MasterCombat-UpdateChecker");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            
            if (isShuttingDown) return;
            if (connection.getResponseCode() != 200) {
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder(512);
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isShuttingDown) return;
                    response.append(line);
                }
                latestVersion = parseVersion(response.toString());
                downloadUrl = parseDownloadUrl(response.toString());
                updateFound = (latestVersion != null);
                lastCheckTime = System.currentTimeMillis();
            }
        } catch (IOException ignored) {
        } finally {
            activeConnections.forEach(conn -> {
                try { conn.disconnect(); } catch (Exception ignored) {}
            });
            activeConnections.clear();
        }
    }

    private static void performJarReplacement(Plugin plugin, CommandSender sender) {
        if (isShuttingDown) return;
        
        try {
            File updateFolder = resolveUpdateFolder(plugin);
            if (updateFolder == null) {
                sendMessage(sender, "§cFailed to create update folder");
                return;
            }

            String versionOnly = latestVersion.startsWith("v") ? latestVersion.substring(1) : latestVersion;
            File tempFile = new File(updateFolder, "MasterCombat-v" + versionOnly + ".jar");
            
            HttpURLConnection connection = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
            activeConnections.add(connection);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            try (ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream());
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                sendMessage(sender, "§aUpdate downloaded successfully!");
                sendMessage(sender, "§aFile saved to: §7" + updateFolder.getName());
                sendMessage(sender, "§aRestart server to apply update");
                sendMessage(sender, "§7Note: Config will be automatically updated on restart");
            } else {
                sendMessage(sender, "§cDownload failed: Empty file created");
            }
        } catch (IOException | IllegalArgumentException ignored) {
            if (!isShuttingDown) {
                sendMessage(sender, "§cDownload error: " + ignored.getMessage());
            }
        } finally {
            activeConnections.forEach(conn -> {
                try { conn.disconnect(); } catch (Exception ignored) {}
            });
            activeConnections.clear();
        }
    }

    private static File resolveUpdateFolder(Plugin plugin) {
        File bukkitYml = new File(plugin.getServer().getWorldContainer(), "bukkit.yml");
        File updateFolder;
        
        if (bukkitYml.exists()) {
            YamlConfiguration bukkitConfig = YamlConfiguration.loadConfiguration(bukkitYml);
            String updateFolderPath = bukkitConfig.getString("settings.update-folder");
            updateFolder = (updateFolderPath != null && !updateFolderPath.isEmpty()) 
                ? new File(plugin.getServer().getWorldContainer(), updateFolderPath)
                : new File(plugin.getDataFolder().getParentFile(), "update");
        } else {
            updateFolder = new File(plugin.getDataFolder().getParentFile(), "update");
        }
        
        return (updateFolder.exists() || updateFolder.mkdirs()) ? updateFolder : null;
    }

    private static void sendMessage(CommandSender sender, String message) {
        if (sender == null) {
            Bukkit.getConsoleSender().sendMessage(message);
        } else {
            sender.sendMessage(message);
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                Combat combat = Combat.getInstance();
                if (combat != null && combat.isDebugEnabled()) {
                    Bukkit.getConsoleSender().sendMessage("[" + sender.getName() + "] " + message);
                }
            }
        }
    }

    private static String normalizeVersion(String version) {
        return (version == null) ? "" : version.replaceAll("[^0-9.]", "");
    }

    private static int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int num1 = (i < parts1.length) ? safeParse(parts1[i]) : 0;
            int num2 = (i < parts2.length) ? safeParse(parts2[i]) : 0;
            if (num1 != num2) return Integer.compare(num1, num2);
        }
        return 0;
    }

    private static int safeParse(String num) {
        try { return Integer.parseInt(num); } 
        catch (NumberFormatException e) { return 0; }
    }

    private static String parseVersion(String jsonResponse) {
        int start = jsonResponse.indexOf("\"tag_name\":\"") + 12;
        if (start < 12) return null;
        int end = jsonResponse.indexOf("\"", start);
        return (end > start) ? jsonResponse.substring(start, end) : null;
    }

    private static String parseDownloadUrl(String jsonResponse) {
        int start = jsonResponse.indexOf("\"browser_download_url\":\"") + 23;
        if (start < 23) return null;
        int end = jsonResponse.indexOf("\"", start);
        return (end > start) ? jsonResponse.substring(start, end) : null;
    }
    
    public static void cleanupTasks() {
        setShuttingDown(true);
    }

    private static int getVersionsBehind(String current, String latest) {
        if (current == null || latest == null) return 0;
        String[] cur = current.split("\\.");
        String[] lat = latest.split("\\.");
        int min = Math.min(cur.length, lat.length);
        for (int i = 0; i < min; i++) {
            int c = safeParse(cur[i]);
            int l = safeParse(lat[i]);
            if (c < l) return l - c;
            if (c > l) return 0;
        }
        return (cur.length < lat.length) ? safeParse(lat[min]) : 0;
    }

    public static boolean isFolia() {
        return SchedulerUtil.isFolia();
    }
}