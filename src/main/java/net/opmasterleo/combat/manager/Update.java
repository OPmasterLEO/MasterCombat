package net.opmasterleo.combat.manager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.opmasterleo.combat.util.SchedulerUtil;

public class Update {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/OPmasterLEO/MasterCombat/releases/latest";
    private static String latestVersion;
    private static String downloadUrl;
    private static boolean updateCheckInProgress = false;
    private static boolean updateDownloadInProgress = false;
    private static volatile boolean isShuttingDown = false;
    private static final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
    private static final Set<BukkitTask> updateTasks = ConcurrentHashMap.newKeySet();
    private static boolean updateFound = false;

    public static void setShuttingDown(boolean shuttingDown) {
        isShuttingDown = shuttingDown;
        if (shuttingDown) {
            for (HttpURLConnection conn : activeConnections) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
            activeConnections.clear();
            for (BukkitTask task : updateTasks) {
                try {
                    if (!task.isCancelled()) {
                        task.cancel();
                    }
                } catch (Exception ignored) {}
            }
            updateTasks.clear();
        }
    }

    public static String getLatestVersion() {
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

    public static void checkForUpdates(Plugin plugin) {
        if (isShuttingDown) return;
        
        if (!updateCheckInProgress) {
            updateCheckInProgress = true;
            if (!updateFound) {
                Bukkit.getConsoleSender().sendMessage("§b[MasterCombat] §eChecking for updates…");
            }
            BukkitTask task = SchedulerUtil.runTaskAsync(plugin, () -> {
                try {
                    performUpdateCheck(plugin);
                } finally {
                    updateCheckInProgress = false;
                }
            });
            if (task != null) {
                updateTasks.add(task);
            }
        }
    }

    public static void notifyOnServerOnline(Plugin plugin) {
        if (isShuttingDown) return;
        final String prefix = plugin.getConfig().getString("Messages.Prefix", "");
        BukkitTask task = SchedulerUtil.runTaskLater(plugin, () -> {
            String pluginName = plugin.getName();
            String currentVersion = plugin.getPluginMeta().getVersion();
            String normalizedCurrent = normalizeVersion(currentVersion);
            if (latestVersion == null && !updateCheckInProgress && !isShuttingDown) {
                updateCheckInProgress = true;
                performUpdateCheck(plugin);
                updateCheckInProgress = false;
            }

            String normalizedLatest = latestVersion != null ? normalizeVersion(latestVersion) : null;

            if (latestVersion == null) {
                Bukkit.getConsoleSender().sendMessage(prefix + "§c[" + pluginName + "]» Unable to fetch update information.");
                return;
            }

            int comparison = compareVersions(normalizedCurrent, normalizedLatest);
            int behind = getVersionsBehind(normalizedCurrent, normalizedLatest);
            if (behind > 0) {
                String msg = prefix + "You are " + behind + " version" + (behind == 1 ? "" : "s") + " behind, update it using /combat update";
                Bukkit.getConsoleSender().sendMessage(msg);
            }

            if (comparison == 0) {
                Bukkit.getConsoleSender().sendMessage("§a[" + pluginName + "]» This server is running the latest " + pluginName + " (v" + currentVersion + ").");
            } else if (comparison < 0) {
                Bukkit.getConsoleSender().sendMessage("§e[" + pluginName + "]» An update is required, installed version v" + currentVersion + ", but latest is v" + latestVersion + ".");
                Bukkit.getConsoleSender().sendMessage("§eUse /combat update to update to the latest version.");
            } else {
                Bukkit.getConsoleSender().sendMessage("§a[" + pluginName + "]» You are running a developer build (v" + currentVersion + "), but the latest public version is v" + latestVersion + ".");
            }
        }, 20L * 3);

        if (task != null) {
            updateTasks.add(task);
        }
    }

    public static void notifyOnPlayerJoin(Player player, Plugin plugin) {
        if (isShuttingDown || !player.isOp() || !plugin.getConfig().getBoolean("update-notify-chat", false)) {
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
                            sendUpdateNotification(player, pluginName, currentVersion);
                            String normalizedCurrent = normalizeVersion(currentVersion);
                            String normalizedLatest = latestVersion != null ? normalizeVersion(latestVersion) : null;
                            int behind = getVersionsBehind(normalizedCurrent, normalizedLatest);
                            if (behind > 0) {
                                String msg = prefix + "You are " + behind + " version" + (behind == 1 ? "" : "s") + " behind, update it using /combat update";
                                player.sendMessage(msg);
                            }
                        }
                    });
                }
            });
            if (task != null) {
                updateTasks.add(task);
            }
        } else {
            sendUpdateNotification(player, pluginName, currentVersion);
            String normalizedCurrent = normalizeVersion(currentVersion);
            String normalizedLatest = latestVersion != null ? normalizeVersion(latestVersion) : null;
            int behind = getVersionsBehind(normalizedCurrent, normalizedLatest);
            if (behind > 0) {
                String msg = prefix + "You are " + behind + " version" + (behind == 1 ? "" : "s") + " behind, update it using /combat update";
                player.sendMessage(msg);
            }
        }
    }
    
    private static void sendUpdateNotification(Player player, String pluginName, String currentVersion) {
        if (isShuttingDown) return;
        
        String normalizedCurrent = normalizeVersion(currentVersion);
        String normalizedLatest = latestVersion != null ? normalizeVersion(latestVersion) : null;
        
        if (latestVersion == null) {
            player.sendMessage("§c[" + pluginName + "]» Unable to fetch update information.");
            return;
        }

        int comparison = compareVersions(normalizedCurrent, normalizedLatest);
        
        if (comparison == 0) {
            player.sendMessage("§a[" + pluginName + "]» This server is running the latest " + pluginName + " (v" + currentVersion + ").");
        } else if (comparison < 0) {
            player.sendMessage("§e[" + pluginName + "]» This server is running " + pluginName + " version v" + currentVersion +
                    " but the latest is v" + latestVersion + ".");
            player.sendMessage("§e[" + pluginName + "]» Use /combat update to update the plugin.");
        } else {
            player.sendMessage("§a[" + pluginName + "]» You are running a developer build (v" + currentVersion + "), but the latest public version is " + latestVersion + ".");
        }
    }

    public static void downloadAndReplaceJar(Plugin plugin) {
        if (isShuttingDown) return;
        
        if (!updateDownloadInProgress) {
            updateDownloadInProgress = true;
            if (updateFound) {
                Bukkit.getConsoleSender().sendMessage("§b[MasterCombat] §eDownloading and applying the update...");
            }
            BukkitTask task = SchedulerUtil.runTaskAsync(plugin, () -> {
                try {
                    performJarReplacement(plugin);
                } catch (Exception e) {
                    if (!isShuttingDown) {
                        Bukkit.getConsoleSender().sendMessage("§c[" + plugin.getName() + "]» Error during update: " + e.getMessage());
                    }
                } finally {
                    updateDownloadInProgress = false;
                }
            });
            if (task != null) {
                updateTasks.add(task);
            }
        }
    }

    public static boolean isUpdateFound() {
        return updateFound;
    }

    public static void setUpdateFound(boolean found) {
        updateFound = found;
    }

    private static void performUpdateCheck(Plugin plugin) {
        if (isShuttingDown) return;

        String pluginName = plugin.getPluginMeta().getName();
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(GITHUB_API_URL).toURL();
            connection = (HttpURLConnection) url.openConnection();
            activeConnections.add(connection);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setRequestProperty("User-Agent", "MasterCombat-UpdateChecker");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (isShuttingDown) {
                return;
            }

            if (connection.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (isShuttingDown) return;
                        response.append(line);
                    }
                    latestVersion = parseVersion(response.toString());
                    downloadUrl = parseDownloadUrl(response.toString());
                }
            } else {
                Bukkit.getConsoleSender().sendMessage("§c[" + pluginName + "]» Failed to check for updates. HTTP Response Code: " + connection.getResponseCode());
                Bukkit.getConsoleSender().sendMessage("§c[" + pluginName + "]» Response Message: " + connection.getResponseMessage());
            }
        } catch (java.net.UnknownHostException e) {
            Bukkit.getConsoleSender().sendMessage("§c[" + pluginName + "]» Unable to resolve host: " + e.getMessage());
        } catch (java.net.SocketTimeoutException e) {
            Bukkit.getConsoleSender().sendMessage("§c[" + pluginName + "]» Connection timed out: " + e.getMessage());
        } catch (Exception e) {
            if (!isShuttingDown) {
                Bukkit.getConsoleSender().sendMessage("§c[" + pluginName + "]» An error occurred while checking for updates: " + e.getMessage());
            }
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {}
                activeConnections.remove(connection);
            }
            updateCheckInProgress = false;
        }
    }

    private static void performJarReplacement(Plugin plugin) {
        if (isShuttingDown) return;
        
        String pluginName = plugin.getPluginMeta().getName();
        HttpURLConnection connection = null;
        try {
            if (downloadUrl == null || latestVersion == null) {
                performUpdateCheck(plugin);
                if (downloadUrl == null) {
                    Bukkit.getConsoleSender().sendMessage("§c[" + pluginName + "]» Could not find download URL for the latest version.");
                    return;
                }
            }
            
            File updateFolder = new File(plugin.getDataFolder().getParentFile(), "update");
            if (!updateFolder.exists() && !updateFolder.mkdirs()) {
                Bukkit.getConsoleSender().sendMessage("§c[" + pluginName + "]» Failed to create update folder.");
                return;
            }
            
            String fixedName = "MasterCombat";
            String versionOnly = latestVersion;
            if (versionOnly != null && versionOnly.toLowerCase().startsWith("mastercombat-v")) {
                versionOnly = versionOnly.substring("mastercombat-v".length());
            }
            File tempFile = new File(updateFolder, fixedName + "-v" + versionOnly + ".jar");
            URL website = URI.create(downloadUrl).toURL();
            connection = (HttpURLConnection) website.openConnection();
            activeConnections.add(connection);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            
            if (isShuttingDown) return;
            
            try (ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream());
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                long maxChunk = 10 * 1024 * 1024;
                long position = 0;
                while (true) {
                    if (isShuttingDown) return;
                    long transferred = fos.getChannel().transferFrom(rbc, position, maxChunk);
                    if (transferred == 0) break;
                    position += transferred;
                }
            }
            
            if (tempFile.exists() && tempFile.length() > 0) {
                Bukkit.getConsoleSender().sendMessage("§a[" + pluginName + "]» Update successfully downloaded!");
                Bukkit.getConsoleSender().sendMessage("§a[" + pluginName + "]» The new version has been placed in the update folder.");
                Bukkit.getConsoleSender().sendMessage("§a[" + pluginName + "]» Please restart your server to apply the update.");
            } else {
                Bukkit.getConsoleSender().sendMessage("§c[" + pluginName + "]» Failed to download the latest jar. File does not exist or is empty.");
            }
        } catch (Exception e) {
            if (!isShuttingDown) {
                Bukkit.getConsoleSender().sendMessage("§c[" + pluginName + "]» An error occurred while downloading the jar: " + e.getMessage());
            }
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {}
                activeConnections.remove(connection);
            }
            updateDownloadInProgress = false;
        }
    }

    public static boolean isFolia() {
        return SchedulerUtil.isFolia();
    }

    private static String normalizeVersion(String version) {
        if (version == null) return "";
        return version.replaceAll("[^0-9.]", "");
    }

    private static int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) return 0;

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int num1 = 0;
            int num2 = 0;
            try {
                num1 = i < parts1.length && !parts1[i].isEmpty() ? Integer.parseInt(parts1[i]) : 0;
            } catch (NumberFormatException ignored) {}
            try {
                num2 = i < parts2.length && !parts2[i].isEmpty() ? Integer.parseInt(parts2[i]) : 0;
            } catch (NumberFormatException ignored) {}
            if (num1 != num2) {
                return num1 - num2;
            }
        }
        return 0;
    }

    private static String parseVersion(String jsonResponse) {
        String tagPrefix = "\"tag_name\":\"";
        int startIndex = jsonResponse.indexOf(tagPrefix);
        if (startIndex == -1) return null;
        startIndex += tagPrefix.length();
        int endIndex = jsonResponse.indexOf("\"", startIndex);
        return jsonResponse.substring(startIndex, endIndex);
    }

    private static String parseDownloadUrl(String jsonResponse) {
        String urlPrefix = "\"browser_download_url\":\"";
        int startIndex = jsonResponse.indexOf(urlPrefix);
        if (startIndex == -1) return null;
        startIndex += urlPrefix.length();
        int endIndex = jsonResponse.indexOf("\"", startIndex);
        return jsonResponse.substring(startIndex, endIndex);
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
            try {
                int c = Integer.parseInt(cur[i]);
                int l = Integer.parseInt(lat[i]);
                if (c < l) {
                    return l - c;
                } else if (c > l) {
                    return 0;
                }
            } catch (NumberFormatException ignored) {}
        }
        if (cur.length < lat.length) {
            try {
                int l = Integer.parseInt(lat[min]);
                return l;
            } catch (Exception ignored) {}
        }
        return 0;
    }
}