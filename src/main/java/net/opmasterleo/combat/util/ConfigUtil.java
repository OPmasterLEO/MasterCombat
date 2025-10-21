package net.opmasterleo.combat.util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class ConfigUtil {
    private static final Map<String, String> comments = new ConcurrentHashMap<>();
    private static final List<String> headerLines = new ArrayList<>();
    private static final String CONFIG_VERSION_KEY = "config-version";
    private static final int CURRENT_CONFIG_VERSION = 2;
    
    public static boolean updateConfig(JavaPlugin plugin) {
        return updateConfig(plugin, false);
    }
    
    public static boolean updateConfig(JavaPlugin plugin, boolean forceUpdate) {
        Logger logger = plugin.getLogger();
        try {
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            FileConfiguration config;
            if (headerLines.isEmpty()) {
                initializeHeader();
            }
            if (!configFile.exists()) {
                plugin.getDataFolder().mkdirs();
                config = generateDefaultConfig();
                config.set(CONFIG_VERSION_KEY, CURRENT_CONFIG_VERSION);
                saveWithComments(config, configFile);
                logger.info("Generated new config.yml with default values");
                return true;
            }
            if (configFile.exists()) {
                preserveExistingHeader(configFile);
            }
            config = YamlConfiguration.loadConfiguration(configFile);
            int configVersion = config.getInt(CONFIG_VERSION_KEY, 1);
            boolean needsUpdate = configVersion < CURRENT_CONFIG_VERSION || forceUpdate;
            if (needsUpdate) {
                logger.info(String.format("Updating config from version %d to %d", configVersion, CURRENT_CONFIG_VERSION));
                config = migrateConfig(config, configVersion);
            }
            FileConfiguration defaultConfig = generateDefaultConfig();
            boolean updated = mergeConfigs(defaultConfig, config);
            if (updated || needsUpdate) {
                config.set(CONFIG_VERSION_KEY, CURRENT_CONFIG_VERSION);
                saveWithComments(config, configFile);
                logger.info("Updated config.yml with new default values while preserving your customizations");
                return true;
            }
            return false;
        } catch (IOException e) {
            logger.severe(() -> "Failed to update config.yml due to I/O error: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.severe(() -> "Failed to update config.yml: " + e.getMessage());
            return false;
        }
    }
    
    private static FileConfiguration migrateConfig(FileConfiguration oldConfig, int fromVersion) {
        if (fromVersion < 2) {
            if (oldConfig.contains("General.disable-elytra") && !oldConfig.isConfigurationSection("General.disable-elytra")) {
                boolean enabled = oldConfig.getBoolean("General.disable-elytra", false);
                oldConfig.set("General.disable-elytra.enabled", enabled);
                oldConfig.set("General.disable-elytra.text", "&cYou cannot use elytra while in combat!");
                oldConfig.set("General.disable-elytra.type", "actionbar");
            }
            if (oldConfig.contains("Messages.NowInCombat") && !oldConfig.isConfigurationSection("Messages.NowInCombat")) {
                String text = oldConfig.getString("Messages.NowInCombat", "");
                oldConfig.set("Messages.NowInCombat.text", text);
                oldConfig.set("Messages.NowInCombat.type", "chat");
            }
            if (oldConfig.contains("Messages.NoLongerInCombat") && !oldConfig.isConfigurationSection("Messages.NoLongerInCombat")) {
                String text = oldConfig.getString("Messages.NoLongerInCombat", "");
                oldConfig.set("Messages.NoLongerInCombat.text", text);
                oldConfig.set("Messages.NoLongerInCombat.type", "chat");
            }
        }
        return oldConfig;
    }
    
    public static int getCurrentConfigVersion() {
        return CURRENT_CONFIG_VERSION;
    }
    
    public static int getConfigVersion(JavaPlugin plugin) {
        return plugin.getConfig().getInt(CONFIG_VERSION_KEY, 1);
    }
    
    private static void initializeHeader() {
        headerLines.add("# ========================================================================");
        headerLines.add("#                      MasterCombat Configuration");
        headerLines.add("#         Developed by OPmasterLEO • https://github.com/OPmasterLEO");
        headerLines.add("# ========================================================================");
        headerLines.add("");
    }
    
    private static void preserveExistingHeader(File configFile) {
        try {
            List<String> existingLines = java.nio.file.Files.readAllLines(configFile.toPath());
            List<String> existingHeader = new ArrayList<>();
            for (String line : existingLines) {
                if (line.trim().startsWith("#") || line.trim().isEmpty()) {
                    existingHeader.add(line);
                } else {
                    break;
                }
            }
            if (!existingHeader.isEmpty()) {
                headerLines.clear();
                headerLines.addAll(existingHeader);
            }
        } catch (IOException e) {
        }
    }
    
    private static boolean mergeConfigs(ConfigurationSection defaults, ConfigurationSection userConfig) {
        return mergeConfigs(defaults, userConfig, "");
    }
    
    private static boolean mergeConfigs(ConfigurationSection defaults, ConfigurationSection userConfig, String parentPath) {
        boolean updated = false;
        List<String> defaultKeys = new ArrayList<>(defaults.getKeys(false));
        for (String key : defaultKeys) {
            String fullPath = parentPath.isEmpty() ? key : parentPath + "." + key;
            if (!userConfig.contains(key)) {
                userConfig.set(key, defaults.get(key));
                updated = true;
            } else if (defaults.isConfigurationSection(key)) {
                if (userConfig.isConfigurationSection(key)) {
                    updated |= mergeConfigs(defaults.getConfigurationSection(key), userConfig.getConfigurationSection(key), fullPath);
                }
            } else if (defaults.isList(key)) {
                if (!userConfig.isList(key)) {
                    userConfig.set(key, defaults.getList(key));
                    updated = true;
                } else {
                    if (key.equals("ignored-projectiles") || key.equals("worlds") || 
                        key.equals("blocked-commands")) {
                        List<?> defaultList = defaults.getList(key);
                        List<?> userList = userConfig.getList(key);
                        List<Object> mergedList = mergeListsPreserveUser(userList, defaultList);
                        if (mergedList != null) {
                            userConfig.set(key, mergedList);
                            updated = true;
                        }
                    }
                }
            }
        }
        return updated;
    }
    
    private static List<Object> mergeListsPreserveUser(List<?> userList, List<?> defaultList) {
        if (userList == null || defaultList == null || defaultList.isEmpty()) {
            return null;
        }
        Map<String, Object> itemMap = new LinkedHashMap<>();
        for (Object userItem : userList) {
            String key = userItem instanceof String ? ((String) userItem).toLowerCase() : String.valueOf(userItem);
            itemMap.put(key, userItem);
        }
        boolean needsMerge = false;
        for (Object defaultItem : defaultList) {
            String key = defaultItem instanceof String ? ((String) defaultItem).toLowerCase() : String.valueOf(defaultItem);
            if (!itemMap.containsKey(key)) {
                itemMap.put(key, defaultItem);
                needsMerge = true;
            }
        }
        if (!needsMerge) {
            return null;
        }
        return new ArrayList<>(itemMap.values());
    }
    
    private static void saveWithComments(FileConfiguration config, File file) throws IOException {
        config.save(file);
        List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
        List<String> newLines = new ArrayList<>(lines.size() + headerLines.size() + comments.size() * 3);
        newLines.addAll(headerLines);
        if (!headerLines.isEmpty() && !lines.isEmpty() && !lines.get(0).trim().startsWith("#")) {
            newLines.add("");
        }
        int indentLevel = 0;
        String lastSection = "";
        for (int idx = 0; idx < lines.size(); idx++) {
            String line = lines.get(idx);
            if (line.startsWith("# ") && line.contains("Bukkit")) {
                continue;
            }
            String trimmed = line.trim();
            boolean isListItem = trimmed.startsWith("- ");
            if (isListItem) {
                int currentIndent = getIndent(line);
                int parentIndent = currentIndent;
                for (int i = idx - 1; i >= 0; i--) {
                    String prevLine = lines.get(i).trim();
                    if (prevLine.endsWith(":") && !prevLine.startsWith("- ")) {
                        parentIndent = getIndent(lines.get(i));
                        break;
                    }
                }
                String formattedLine = " ".repeat(parentIndent + 2) + trimmed;
                newLines.add(formattedLine);
                continue;
            }
            if (trimmed.endsWith(":") && !trimmed.contains("#")) {
                String fullPath = getFullPath(lines, line, indentLevel);
                String currentSection = fullPath.contains(".") ? fullPath.split("\\.")[0] : fullPath;
                if (!currentSection.equals(lastSection) && !lastSection.isEmpty() && getIndent(line) == 0) {
                    newLines.add("");
                }
                lastSection = currentSection;
                String comment = comments.get(fullPath);
                if (comment != null) {
                    if (!newLines.isEmpty() && !newLines.get(newLines.size() - 1).trim().isEmpty() && getIndent(line) > 0) {
                        newLines.add("");
                    }
                    addCommentLines(newLines, comment, getIndent(line));
                }
            }
            newLines.add(line);
            if (trimmed.endsWith(":")) {
                indentLevel++;
            } else if (indentLevel > 0 && !trimmed.isEmpty() && !isListItem &&
                      (getIndent(line) < indentLevel * 2)) {
                indentLevel = getIndent(line) / 2;
            }
        }
        java.nio.file.Files.write(file.toPath(), newLines);
    }
    
    private static void addCommentLines(List<String> lines, String comment, int indent) {
        String indentStr = " ".repeat(indent);
        for (String commentLine : comment.split("\n")) {
            lines.add(indentStr + "# " + commentLine);
        }
    }
    
    private static int getIndent(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }
    
    private static String getFullPath(List<String> lines, String currentLine, int currentIndent) {
        StringBuilder path = new StringBuilder();
        int lineIdx = lines.indexOf(currentLine);
        String currentKey = currentLine.trim();
        if (currentKey.endsWith(":")) {
            currentKey = currentKey.substring(0, currentKey.length() - 1).trim();
        }
        path.insert(0, currentKey);
        for (int i = lineIdx - 1; i >= 0; i--) {
            String line = lines.get(i);
            int indent = getIndent(line);
            if (indent < currentIndent && line.trim().endsWith(":")) {
                currentIndent = indent;
                String parentKey = line.trim();
                if (parentKey.endsWith(":")) {
                    parentKey = parentKey.substring(0, parentKey.length() - 1).trim();
                }
                path.insert(0, parentKey + ".");
            }
        }
        return path.toString();
    }
    
    private static FileConfiguration generateDefaultConfig() {
        FileConfiguration config = new YamlConfiguration();
        addComment("General", "═══════════════════════════════════════════════════════════════════════\nGeneral Settings\n═══════════════════════════════════════════════════════════════════════");
        config.set("General.combat-enabled", true);
        addComment("General.combat-enabled", "Enable or disable the combat tagging system entirely");
        config.set("General.duration", 30);
        addComment("General.duration", "How long players stay in combat (in seconds)\nDefault: 30 seconds");
        config.set("General.CombatTagGlowing", false);
        addComment("General.CombatTagGlowing", "Make players glow when in combat (requires PacketEvents plugin)\nRecommended: false (can cause performance issues)");
        config.set("General.ignore-op", true);
        addComment("General.ignore-op", "Should server operators be immune to combat tagging?\nRecommended: true");
        config.set("General.disable-elytra.enabled", true);
        config.set("General.disable-elytra.text", "&cYou cannot use elytra while in combat!");
        config.set("General.disable-elytra.type", "actionbar");
        addComment("General.disable-elytra", "Elytra restrictions during combat\nenabled: Should elytra be disabled during combat?\ntext: Message shown when player tries to use elytra\ntype: Where to display the message (chat, actionbar, or both)");
        config.set("General.Format", "&c&lCOMBAT &7» &fYou're in combat for &c%seconds% &fseconds");
        addComment("General.Format", "Action bar format showing remaining combat time\nUse %seconds% as placeholder for the remaining time\nSupports color codes with &");
        config.set("self-combat", false);
        addComment("self-combat", "Can players enter combat by damaging themselves?\nUseful for preventing combat tag abuse\nRecommended: false");
        addComment("EnabledWorlds", "═══════════════════════════════════════════════════════════════════════\nWorld Settings\n═══════════════════════════════════════════════════════════════════════");
        config.set("EnabledWorlds.enabled", false);
        addComment("EnabledWorlds.enabled", "Enable combat tagging only in specific worlds?\nIf false, combat tagging works in all worlds");
        config.set("EnabledWorlds.worlds", Arrays.asList("world", "world_nether", "world_the_end"));
        addComment("EnabledWorlds.worlds", "List of worlds where combat tagging is active\nOnly used if 'enabled' is set to true\nWorld names are case-sensitive");
        addComment("Messages", "═══════════════════════════════════════════════════════════════════════\nMessages Configuration\n═══════════════════════════════════════════════════════════════════════");
        config.set("Messages.Prefix", "&c&lCOMBAT &8» &r");
        addComment("Messages.Prefix", "Prefix added to all plugin messages\nSupports color codes with &");
        config.set("Messages.NowInCombat.text", "&cYou are now in combat! Don't log out!");
        config.set("Messages.NowInCombat.type", "chat");
        addComment("Messages.NowInCombat", "Message when player enters combat\ntext: The message content\ntype: Display method (chat, actionbar, or both)");
        config.set("Messages.NoLongerInCombat.text", "&aYou are no longer in combat!");
        config.set("Messages.NoLongerInCombat.type", "chat");
        addComment("Messages.NoLongerInCombat", "Message when player exits combat\ntext: The message content\ntype: Display method (chat, actionbar, or both)");
        addComment("EnderPearl", "═══════════════════════════════════════════════════════════════════════\nEnder Pearl Settings\n═══════════════════════════════════════════════════════════════════════");
        config.set("EnderPearl.Enabled", false);
        config.set("EnderPearl.Distance", 5);
        addComment("EnderPearl.Enabled", "Should ender pearls trigger combat tag?\nEnabled: Throwing pearls near enemies tags you\nDistance: How close an enemy must be (in blocks)");
        addComment("ignored-projectiles", "═══════════════════════════════════════════════════════════════════════\nProjectile Filtering\n═══════════════════════════════════════════════════════════════════════\nProjectiles that will NOT trigger combat tagging\nUse Bukkit entity type names (case-insensitive)\nCommon types: SNOWBALL, EGG, FISHING_HOOK, ENDER_PEARL");
        config.set("ignored-projectiles", Arrays.asList("SNOWBALL", "EGG", "FISHING_HOOK"));
        addComment("blocked-commands-enabled", "═══════════════════════════════════════════════════════════════════════\nCommand Blocking\n═══════════════════════════════════════════════════════════════════════\nEnable or disable command blocking during combat");
        config.set("blocked-commands-enabled", true);
        config.set("blocked-commands", Arrays.asList(
            "spawn", "home", "homes", "tp", "tpa", "tphere", "tpask", "warp", "warps", 
            "eback", "back", "return", "lobby", "hub", "leave", "shop"
        ));
        addComment("blocked-commands", "Commands that cannot be used while in combat\nDo not include the slash (/)\nCommands are case-insensitive\nAdd or remove commands as needed for your server");
        config.set("blocked-commands-bypass-perm", "mastercombat.bypass.commands");
        addComment("blocked-commands-bypass-perm", "Permission to bypass command blocking\nPlayers with this permission can use blocked commands during combat");
        addComment("link-respawn-anchor", "═══════════════════════════════════════════════════════════════════════\nExplosion Integration\n═══════════════════════════════════════════════════════════════════════\nLink respawn anchor explosions to the player who triggered them\nAllows combat tagging from respawn anchor kills");
        config.set("link-respawn-anchor", true);
        config.set("link-bed-explosions", true);
        addComment("link-bed-explosions", "Link bed explosions to the player who triggered them\nAllows combat tagging from bed trap kills in Nether/End");
        addComment("NewbieProtection", "═══════════════════════════════════════════════════════════════════════\nNewbie Protection System\n═══════════════════════════════════════════════════════════════════════");
        ConfigurationSection newbie = config.createSection("NewbieProtection");
        newbie.set("enabled", true);
        addComment("NewbieProtection.enabled", "Enable protection for new players");
        newbie.set("timeout", 300);
        addComment("NewbieProtection.timeout", "Protection duration in seconds (300 = 5 minutes)\nNew players are protected from PvP for this duration\n0 = permanent protection until manually removed");
        ConfigurationSection newbieSettings = newbie.createSection("settings");
        newbieSettings.set("disableCommand", "removeprotect");
        addComment("NewbieProtection.settings.disableCommand", "Command players use to remove their protection early\nWill be registered as: /removeprotect");
        newbieSettings.set("bypassPerm", "mastercombat.bypass.newbie");
        addComment("NewbieProtection.settings.bypassPerm", "Permission to bypass newbie protection checks\nAllows attacking protected players");
        newbieSettings.set("protectOthers", true);
        addComment("NewbieProtection.settings.protectOthers", "Prevent protected players from attacking others?\ntrue: Protected players cannot start combat\nfalse: Protected players can attack, but cannot be attacked");
        addComment("safezone_protection", "═══════════════════════════════════════════════════════════════════════\nWorldGuard Integration\n═══════════════════════════════════════════════════════════════════════\nRequires WorldGuard plugin installed");
        ConfigurationSection safezone = config.createSection("safezone_protection");
        safezone.set("enabled", true);
        addComment("safezone_protection.enabled", "Enable WorldGuard region protection\nPrevents combat tagging in regions with PvP disabled");
        safezone.set("message", "&cYou cannot attack players in safe zones!");
        addComment("safezone_protection.message", "Message shown when trying to attack in a safe zone");
        addComment("debug", "═══════════════════════════════════════════════════════════════════════\nAdvanced Settings\n═══════════════════════════════════════════════════════════════════════\nEnable debug logging for troubleshooting\nWARNING: Creates verbose console output, use only when diagnosing issues");
        config.set("debug", false);
        config.set("update-notify-chat", true);
        addComment("update-notify-chat", "Notify server operators about plugin updates in chat\nChecks GitHub for new releases on player join");
        return config;
    }
    
    private static void addComment(String path, String comment) {
        comments.put(path, comment);
    }
}