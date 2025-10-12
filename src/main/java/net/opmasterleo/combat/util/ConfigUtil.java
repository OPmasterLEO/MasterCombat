package net.opmasterleo.combat.util;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class ConfigUtil {
    private static final Pattern KEY_PATTERN = Pattern.compile("^\\s*([\\w-]+)\\s*:");
    private static final Pattern VERSION_PATTERN = Pattern.compile("generated-by-version\\s*:");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*#");
    private static final Pattern SECTION_HEADER = Pattern.compile("^\\s*[#=_]+");
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^\\s*-\\s*(.+)");

    public static void updateConfig(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;
        
        List<String> defaultLines = readLinesFromResource(plugin.getResource("config.yml"));
        if (defaultLines == null || defaultLines.isEmpty()) return;
        List<String> userLines = readLinesFromFile(configFile);
        if (userLines.isEmpty()) return;
        
        List<String> output = new ArrayList<>();
        Map<String, List<String>> userValues = parseValuesWithLists(userLines);
        Set<String> processedKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Deque<String> pathStack = new ArrayDeque<>();
        
        for (String defaultLine : defaultLines) {
            String trimmed = defaultLine.trim();
            
            if (trimmed.isEmpty() || COMMENT_PATTERN.matcher(defaultLine).find() || 
                SECTION_HEADER.matcher(defaultLine).find()) {
                output.add(defaultLine);
                continue;
            }
            
            if (VERSION_PATTERN.matcher(defaultLine).find()) {
                String versionLine = "generated-by-version: \"v" + plugin.getPluginMeta().getVersion() + "\"";
                output.add(versionLine);
                processedKeys.add("generated-by-version");
                continue;
            }
            
            var listMatcher = LIST_ITEM_PATTERN.matcher(defaultLine);
            if (listMatcher.find()) {
                String listItem = listMatcher.group(1).trim();
                String parentPath = buildPath(pathStack);
                List<String> userListItems = userValues.get(parentPath);
                
                if (userListItems != null && !userListItems.isEmpty()) {
                    if (!processedKeys.contains(parentPath)) {
                        for (String userListItem : userListItems) {
                            output.add(getIndentation(defaultLine) + "- " + userListItem);
                        }
                        processedKeys.add(parentPath);
                    }
                    continue;
                } else {
                    output.add(defaultLine);
                }
                continue;
            }
            
            String key = extractKey(defaultLine);
            if (key == null) {
                output.add(defaultLine);
                continue;
            }
            
            int indent = countIndent(defaultLine);
            while (!pathStack.isEmpty() && countIndent(pathStack.peek()) >= indent) {
                pathStack.pop();
            }
            pathStack.push(defaultLine);
            
            String fullKey = buildPath(pathStack);
            List<String> userLinesForPath = userValues.get(fullKey);
            
            if (userLinesForPath != null && !userLinesForPath.isEmpty()) {
                output.add(userLinesForPath.get(0));
                processedKeys.add(fullKey);
            } else {
                output.add(defaultLine);
            }
        }
        
        addMissingUserSections(userValues, processedKeys, output);
        
        saveConfig(configFile, output, plugin);
    }

    public static void updateConfigAsync(JavaPlugin plugin) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> updateConfig(plugin));
    }

    private static String extractKey(String line) {
        var matcher = KEY_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String buildPath(Deque<String> pathStack) {
        StringBuilder path = new StringBuilder();
        Iterator<String> it = pathStack.descendingIterator();
        while (it.hasNext()) {
            String key = extractKey(it.next());
            if (key != null) {
                if (path.length() > 0) path.append('.');
                path.append(key);
            }
        }
        return path.toString();
    }

    private static Map<String, List<String>> parseValuesWithLists(List<String> lines) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        Deque<String> pathStack = new ArrayDeque<>();
        String currentListPath = null;
        List<String> currentListItems = null;
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            
            if (trimmed.isEmpty() || COMMENT_PATTERN.matcher(line).find() || 
                SECTION_HEADER.matcher(line).find()) {
                continue;
            }
            
            int indent = countIndent(line);
            while (!pathStack.isEmpty() && countIndent(pathStack.peek()) >= indent) {
                pathStack.pop();
            }
            
            var listMatcher = LIST_ITEM_PATTERN.matcher(line);
            if (listMatcher.find()) {
                String listItem = listMatcher.group(1).trim();
                if (currentListPath != null && currentListItems != null) {
                    currentListItems.add(listItem);
                }
                continue;
            }
            
            currentListPath = null;
            currentListItems = null;
            
            String key = extractKey(line);
            if (key != null) {
                pathStack.push(line);
                String fullPath = buildPath(pathStack);
                
                List<String> pathLines = new ArrayList<>();
                pathLines.add(line);
                
                int nextLineIndex = i + 1;
                while (nextLineIndex < lines.size()) {
                    String nextLine = lines.get(nextLineIndex);
                    String nextTrimmed = nextLine.trim();
                    
                    if (nextTrimmed.isEmpty() || COMMENT_PATTERN.matcher(nextLine).find()) {
                        nextLineIndex++;
                        continue;
                    }
                    
                    var nextListMatcher = LIST_ITEM_PATTERN.matcher(nextLine);
                    if (nextListMatcher.find() && countIndent(nextLine) > indent) {
                        String listItem = nextListMatcher.group(1).trim();
                        pathLines.add(nextLine);
                        nextLineIndex++;
                    } else {
                        break;
                    }
                }
                
                values.put(fullPath, pathLines);
                
                if (pathLines.size() > 1) {
                    currentListPath = fullPath;
                    currentListItems = new ArrayList<>();
                    for (int j = 1; j < pathLines.size(); j++) {
                        var itemMatcher = LIST_ITEM_PATTERN.matcher(pathLines.get(j));
                        if (itemMatcher.find()) {
                            currentListItems.add(itemMatcher.group(1).trim());
                        }
                    }
                    values.put(fullPath, pathLines);
                }
            }
        }
        return values;
    }

    private static void addMissingUserSections(Map<String, List<String>> userValues, 
                                             Set<String> processedKeys, 
                                             List<String> output) {
        for (Map.Entry<String, List<String>> entry : userValues.entrySet()) {
            String key = entry.getKey();
            if (!processedKeys.contains(key)) {
                output.add("# User-added configuration:");
                output.addAll(entry.getValue());
                output.add("");
            }
        }
    }

    private static String getIndentation(String line) {
        int indent = countIndent(line);
        return " ".repeat(indent);
    }

    private static List<String> readLinesFromResource(InputStream is) {
        if (is == null) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> readLinesFromFile(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static int countIndent(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        return indent;
    }

    private static void saveConfig(File configFile, List<String> lines, JavaPlugin plugin) {
        File tempFile = new File(configFile.getParentFile(), "config_temp.yml");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write(System.lineSeparator());
            }
            
            if (configFile.exists() && !configFile.delete()) {
                throw new IOException("Could not delete old config");
            }
            
            if (!tempFile.renameTo(configFile)) {
                throw new IOException("Could not rename temp config");
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save updated config", ex);
            tempFile.delete();
        }
    }

    public static void updateOption(FileConfiguration config, String path, Object defaultValue) {
        if (!config.contains(path)) {
            config.set(path, defaultValue);
        }
    }

    public static void reloadConfigSafely(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (configFile.exists()) {
            List<String> userLines = readLinesFromFile(configFile);
            updateConfig(plugin);
            plugin.reloadConfig();
        }
    }
}        if (!config.contains(path)) {
            config.set(path, defaultValue);
        }
    }
}