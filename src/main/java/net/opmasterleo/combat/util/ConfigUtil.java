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
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*#");
    private static final Pattern SECTION_HEADER = Pattern.compile("^\\s*[#=_]+");

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
            String userLine = userValues.get(fullKey);
            
            if (userLine != null) {
                output.add(userLine);
                processedKeys.add(fullKey);
            } else {
                output.add(defaultLine);
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
            
            String key = extractKey(line);
            if (key != null) {
                pathStack.push(line);
                values.put(buildPath(pathStack), line);
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
}