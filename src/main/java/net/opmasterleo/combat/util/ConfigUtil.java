package net.opmasterleo.combat.util;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

public class ConfigUtil {

    public static void updateConfig(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;
        
        // Read config files
        List<String> defaultLines = readLinesFromResource(plugin.getResource("config.yml"), plugin);
        if (defaultLines == null || defaultLines.isEmpty()) return;
        List<String> userLines = readLinesFromFile(configFile, plugin);
        if (userLines.isEmpty()) return;
        
        // Process user config keys
        Map<String, Integer> userKeyLineMap = new LinkedHashMap<>();
        Deque<String> userPath = new ArrayDeque<>();
        processConfigLines(userLines, userKeyLineMap, userPath);
        
        // Process default config keys
        Set<String> defaultKeys = new HashSet<>();
        Deque<String> defaultPath = new ArrayDeque<>();
        processConfigLines(defaultLines, defaultKeys, defaultPath);
        
        // Generate updated config
        List<String> output = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();
        Deque<String> currentPath = new ArrayDeque<>();
        
        for (String line : defaultLines) {
            String trimmed = line.trim();
            int indent = countIndent(line);
            int depth = indent / 2;
            
            // Maintain path stack
            while (currentPath.size() > depth) currentPath.removeLast();
            
            if (!trimmed.contains(":") || trimmed.startsWith("#")) {
                output.add(line);
                continue;
            }
            
            // Process key-value lines
            String[] parts = trimmed.split(":", 2);
            String key = parts[0].trim().replace(" ", "");
            currentPath.addLast(key);
            String fullKey = String.join(".", currentPath);
            
            // Special handling for version field
            if (key.equals("generated-by-version")) {
                output.add(" ".repeat(indent) + "generated-by-version: \"v" + 
                          plugin.getPluginMeta().getVersion() + "\" # DO NOT CHANGE");
                addedKeys.add(fullKey);
            } 
            // Preserve user values
            else if (userKeyLineMap.containsKey(fullKey)) {
                output.add(userLines.get(userKeyLineMap.get(fullKey)));
                addedKeys.add(fullKey);
            } 
            // Add new default values
            else if (!addedKeys.contains(fullKey)) {
                output.add(line);
                addedKeys.add(fullKey);
            }
        }
        
        // Add custom user entries not in default config
        for (Map.Entry<String, Integer> entry : userKeyLineMap.entrySet()) {
            String fullKey = entry.getKey();
            if (!defaultKeys.contains(fullKey) && !addedKeys.contains(fullKey)) {
                String userLine = userLines.get(entry.getValue());
                output.add(userLine);
            }
        }
        
        // Save updated config
        saveConfig(configFile, output, plugin);
    }

    public static void updateConfigAsync(JavaPlugin plugin) {
        SchedulerUtil.runTaskAsync((Plugin) plugin, () -> updateConfig(plugin));
    }

    // Helper methods
    private static List<String> readLinesFromResource(InputStream is, JavaPlugin plugin) {
        if (is == null) {
            plugin.getLogger().warning("Default config.yml not found in JAR");
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Error reading default config.yml", e);
            return null;
        }
    }

    private static List<String> readLinesFromFile(File file, JavaPlugin plugin) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Error reading user config.yml", e);
            return new ArrayList<>();
        }
    }

    private static void processConfigLines(List<String> lines, Map<String, Integer> keyMap, Deque<String> path) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            
            int indent = countIndent(line);
            int depth = indent / 2;
            
            // Maintain path stack
            while (path.size() > depth) path.removeLast();
            
            if (!trimmed.contains(":")) continue;
            
            String[] parts = trimmed.split(":", 2);
            String key = parts[0].trim().replace(" ", "");
            path.addLast(key);
            keyMap.put(String.join(".", path), i);
        }
    }

    private static void processConfigLines(List<String> lines, Set<String> keySet, Deque<String> path) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            
            int indent = countIndent(line);
            int depth = indent / 2;
            
            // Maintain path stack
            while (path.size() > depth) path.removeLast();
            
            if (!trimmed.contains(":")) continue;
            
            String[] parts = trimmed.split(":", 2);
            String key = parts[0].trim().replace(" ", "");
            path.addLast(key);
            keySet.add(String.join(".", path));
        }
    }

    private static int countIndent(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        return indent;
    }

    private static void saveConfig(File configFile, List<String> lines, JavaPlugin plugin) {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write(System.lineSeparator());
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save updated config.yml", ex);
        }
    }
}