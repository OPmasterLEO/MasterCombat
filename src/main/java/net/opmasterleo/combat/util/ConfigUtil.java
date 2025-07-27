package net.opmasterleo.combat.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class ConfigUtil {
    private static final Pattern KEY_PATTERN = Pattern.compile("^\\s*([\\w-]+)\\s*:");
    private static final Pattern VERSION_PATTERN = Pattern.compile("generated-by-version\\s*:");
    private static final Pattern SECTION_PATTERN = Pattern.compile("^\\s*[#=]+");

    public static void updateConfig(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;
        
        List<String> defaultLines = readLinesFromResource(plugin.getResource("config.yml"), plugin);
        if (defaultLines == null || defaultLines.isEmpty()) return;
        List<String> userLines = readLinesFromFile(configFile, plugin);
        if (userLines.isEmpty()) return;
        
        List<String> output = new ArrayList<>();
        Map<String, String> userValues = parseValues(userLines);
        Set<String> processedKeys = new HashSet<>();
        Deque<String> pathStack = new ArrayDeque<>();
        
        for (String line : defaultLines) {
            String trimmed = line.trim();
            
            if (SECTION_PATTERN.matcher(line).find() || trimmed.startsWith("#") || trimmed.isEmpty()) {
                output.add(line);
                continue;
            }
            
            if (VERSION_PATTERN.matcher(line).find()) {
                output.add("generated-by-version: \"v" + plugin.getPluginMeta().getVersion() + "\"");
                processedKeys.add("generated-by-version");
                continue;
            }
            
            String key = extractKey(line);
            if (key == null) {
                output.add(line);
                continue;
            }
            
            int indent = countIndent(line);
            while (!pathStack.isEmpty() && countIndent(pathStack.peek()) >= indent) {
                pathStack.pop();
            }
            pathStack.push(line);
            
            String fullKey = buildPath(pathStack);
            String userValue = userValues.get(fullKey);
            
            if (userValue != null) {
                output.add(userValue);
                processedKeys.add(fullKey);
            } else {
                output.add(line);
            }
        }
        
        for (Map.Entry<String, String> entry : userValues.entrySet()) {
            if (!processedKeys.contains(entry.getKey())) {
                output.add("");
                output.add("# Custom user setting:");
                output.add(entry.getValue());
            }
        }
        
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

    private static Map<String, String> parseValues(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        Deque<String> pathStack = new ArrayDeque<>();
        
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            
            int indent = countIndent(line);
            while (!pathStack.isEmpty() && countIndent(pathStack.peek()) >= indent) {
                pathStack.pop();
            }
            
            String key = extractKey(line);
            if (key != null) {
                pathStack.push(line);
                values.put(buildPath(pathStack), line);
            } else if (!pathStack.isEmpty()) {
                String fullKey = buildPath(pathStack) + "._value";
                String current = values.getOrDefault(fullKey, "");
                values.put(fullKey, current + line + "\n");
            }
        }
        return values;
    }

    private static List<String> readLinesFromResource(InputStream is, JavaPlugin plugin) {
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

    private static List<String> readLinesFromFile(File file, JavaPlugin plugin) {
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
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write(System.lineSeparator());
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Config save error", ex);
        }
    }
}