package net.opmasterleo.combat.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;

public class ConfigUtil {

    public static void updateConfig(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;
        List<String> defaultLines = readLinesSafely(plugin.getResource("config.yml"), plugin);
        if (defaultLines == null) return;
        List<String> userLines = readLinesSafelySafe(configFile, plugin);
        Map<String, Integer> userKeyLineMap = new LinkedHashMap<>();
        Deque<String> userPath = new ArrayDeque<>();
        for (int i = 0; i < userLines.size(); i++) {
            String line = userLines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            int depth = indent / 2;
            while (userPath.size() > depth) userPath.removeLast();
            if (!trimmed.contains(":")) continue;
            String[] parts = trimmed.split(":", 2);
            String key = parts[0].trim().replace(" ", "");
            userPath.addLast(key);
            String fullKey = String.join(".", userPath);
            userKeyLineMap.put(fullKey, i);
        }

        Set<String> alreadyAdded = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<String> output = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        for (int i = 0; i < defaultLines.size(); i++) {
            String line = defaultLines.get(i);
            String trimmed = line.trim();
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            int depth = indent / 2;
            while (path.size() > depth) path.removeLast();
            if (!trimmed.contains(":")) {
                output.add(line);
                continue;
            }
            String[] parts = trimmed.split(":", 2);
            String key = parts[0].trim().replace(" ", "");
            path.addLast(key);
            String fullKey = String.join(".", path);
            if (key.equals("generated-by-version")) {
                output.add(" ".repeat(indent) + "generated-by-version: \"v" + plugin.getPluginMeta().getVersion() + "\" # DO NOT CHANGE");
                alreadyAdded.add(fullKey);
            } else if (userKeyLineMap.containsKey(fullKey)) {
                output.add(userLines.get(userKeyLineMap.get(fullKey)));
                alreadyAdded.add(fullKey);
            } else if (!alreadyAdded.contains(fullKey)) {
                output.add(line);
                alreadyAdded.add(fullKey);
            }
        }

        Set<String> defaultKeys = new HashSet<>();
        Deque<String> defaultPath = new ArrayDeque<>();
        for (String line : defaultLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            int depth = indent / 2;
            while (defaultPath.size() > depth) defaultPath.removeLast();
            if (!trimmed.contains(":")) continue;
            String[] parts = trimmed.split(":", 2);
            String key = parts[0].trim().replace(" ", "");
            defaultPath.addLast(key);
            String fullKey = String.join(".", defaultPath);
            defaultKeys.add(fullKey);
        }
        for (Map.Entry<String, Integer> e : userKeyLineMap.entrySet()) {
            String userLine = userLines.get(e.getValue());
            String trimmed = userLine.trim();
            int indent = 0;
            while (indent < userLine.length() && userLine.charAt(indent) == ' ') indent++;
            if (!defaultKeys.contains(e.getKey()) && !alreadyAdded.contains(e.getKey())) {
                output.add(" ".repeat(indent) + trimmed);
            }
        }

        try (Writer w = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            for (String o : output) {
                w.write(o);
                w.write(System.lineSeparator());
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save updated config.yml", ex);
        }
    }

    public static void updateConfigAsync(JavaPlugin plugin) {
        SchedulerUtil.runTaskAsync(plugin, () -> updateConfig(plugin));
    }

    private static List<String> readLinesSafely(InputStream is, JavaPlugin plugin) {
        if (is == null) {
            plugin.getLogger().warning("Default config.yml not found in JAR");
            return null;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
            return lines;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Error reading default config.yml", e);
            return null;
        }
    }

    private static List<String> readLinesSafelySafe(File file, JavaPlugin plugin) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
            return lines;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Error reading user config.yml", e);
            return new ArrayList<>();
        }
    }
}