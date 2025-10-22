package net.opmasterleo.combat.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public class ConfigUtil {
    private static final ConcurrentHashMap<JavaPlugin, AtomicBoolean> updateLocks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> configCache = new ConcurrentHashMap<>();
    
    public static void updateConfig(JavaPlugin plugin) {
        AtomicBoolean lock = updateLocks.computeIfAbsent(plugin, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) return;
        
        try {
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            if (!configFile.exists()) return;
            
            SchedulerUtil.runTaskAsync(plugin, () -> {
                try {
                    if (tryPreserveCommentsMerge(plugin, configFile)) {
                        lock.set(false);
                        return;
                    }
                    
                    Yaml defaultYaml = createYamlParser();
                    Map<String, Object> defaultConfig = loadConfigFromResource(plugin.getResource("config.yml"), defaultYaml);
                    if (defaultConfig == null) return;
                    
                    Yaml userYaml = createYamlParser();
                    Map<String, Object> userConfig = loadConfigFromFile(configFile, userYaml);
                    if (userConfig == null) return;
                    
                    Map<String, Object> mergedConfig = mergeConfigs(defaultConfig, userConfig);
                    
                    SchedulerUtil.runTask(plugin, () -> {
                        saveMergedConfig(configFile, mergedConfig, plugin, defaultYaml);
                        lock.set(false);
                    });
                    
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to update config", e);
                    lock.set(false);
                }
            });
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to schedule config update", e);
            lock.set(false);
        }
    }
    
    public static void updateConfigParallel(JavaPlugin plugin) {
        AtomicBoolean lock = updateLocks.computeIfAbsent(plugin, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) return;
        
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            lock.set(false);
            return;
        }
        
        SchedulerUtil.runTaskAsync(plugin, () -> {
            try {
                if (tryPreserveCommentsMerge(plugin, configFile)) {
                    lock.set(false);
                    return;
                }
                
                Yaml defaultYaml = createYamlParser();
                Map<String, Object> defaultConfig = loadConfigFromResource(plugin.getResource("config.yml"), defaultYaml);
                if (defaultConfig == null) {
                    lock.set(false);
                    return;
                }
                
                Yaml userYaml = createYamlParser();
                Map<String, Object> userConfig = loadConfigFromFile(configFile, userYaml);
                if (userConfig == null) {
                    lock.set(false);
                    return;
                }
                
                Map<String, Object> mergedConfig = mergeConfigs(defaultConfig, userConfig);
                
                SchedulerUtil.runTask(plugin, () -> {
                    saveMergedConfig(configFile, mergedConfig, plugin, createYamlParser());
                    lock.set(false);
                });
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to process config in parallel", e);
                lock.set(false);
            }
        });
    }

    private static boolean tryPreserveCommentsMerge(JavaPlugin plugin, File userConfigFile) {
        try {
            String defaultText;
            try (InputStream in = plugin.getResource("config.yml")) {
                if (in == null) return false;
                defaultText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            String userText;
            try (FileInputStream fis = new FileInputStream(userConfigFile)) {
                userText = new String(fis.readAllBytes(), StandardCharsets.UTF_8);
            }

            Yaml yaml = createYamlParser();
            @SuppressWarnings("unchecked")
            Map<String, Object> defaultMap = (Map<String, Object>) yaml.load(defaultText);
            @SuppressWarnings("unchecked")
            Map<String, Object> userMap = (Map<String, Object>) yaml.load(userText);
            if (defaultMap == null || userMap == null) return false;
            Map<String, Object> mergedMap = mergeConfigs(defaultMap, userMap);
            mergedMap.put("generated-by-version", "v" + plugin.getPluginMeta().getVersion());
            String result = mergeYamlWithComments(defaultText, mergedMap, yaml);
            try (FileOutputStream fos = new FileOutputStream(userConfigFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(result);
            }
            
            return true;
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().fine(() -> "Comment-preserving merge failed, falling back to SnakeYAML: " + e.getMessage());
            return false;
        }
    }

    private static String mergeYamlWithComments(String template, Map<String, Object> newValues, Yaml yaml) {
        String[] lines = template.split("\r?\n");
        StringBuilder result = new StringBuilder();
        String currentPath = "";
        int indentLevel = 0;
        int lineIndex = 0;
        while (lineIndex < lines.length) {
            String line = lines[lineIndex];
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                result.append(line).append("\n");
                lineIndex++;
                continue;
            }

            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                int indent = 0;
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) == ' ') indent++;
                    else break;
                }
                
                String key = line.substring(indent, colonIndex).trim();
                String rest = line.substring(colonIndex + 1).trim();
                if (indent == 0) {
                    currentPath = key;
                    indentLevel = 0;
                } else {
                    int levels = indent / 2;
                    if (levels <= indentLevel) {
                        String[] parts = currentPath.split("\\.");
                        if (levels < parts.length) {
                            currentPath = String.join(".", java.util.Arrays.copyOf(parts, levels)) + (levels > 0 ? "." : "") + key;
                        } else {
                            currentPath = key;
                        }
                    } else {
                        currentPath = currentPath + "." + key;
                    }
                    indentLevel = levels;
                }

                Object newValue = getValueAtPath(newValues, currentPath);
                if (newValue != null && !(newValue instanceof Map) && !(newValue instanceof List)) {
                    String inlineComment = "";
                    int commentIdx = rest.indexOf('#');
                    if (commentIdx > 0) {
                        inlineComment = " " + rest.substring(commentIdx);
                    }
                    
                    String valueStr = yaml.dump(newValue).trim();
                    result.append(line.substring(0, colonIndex + 1))
                          .append(" ")
                          .append(valueStr)
                          .append(inlineComment)
                          .append("\n");
                    lineIndex++;
                }
                else if (newValue instanceof List) {
                    List<?> newList = (List<?>) newValue;
                    result.append(line).append("\n");
                    lineIndex++;
                    int listIndent = indent + 2;
                    while (lineIndex < lines.length) {
                        String nextLine = lines[lineIndex];
                        String nextTrimmed = nextLine.trim();
                        if (nextTrimmed.isEmpty() || nextTrimmed.startsWith("#")) {
                            int nextIndent = 0;
                            for (int i = 0; i < nextLine.length(); i++) {
                                if (nextLine.charAt(i) == ' ') nextIndent++;
                                else break;
                            }
                            if (nextIndent < listIndent || nextTrimmed.isEmpty()) {
                                break;
                            }
                        }

                        if (nextTrimmed.startsWith("-") || nextLine.indexOf(':') > 0) {
                            int nextIndent = 0;
                            for (int i = 0; i < nextLine.length(); i++) {
                                if (nextLine.charAt(i) == ' ') nextIndent++;
                                else break;
                            }
                            
                            if (nextTrimmed.startsWith("-")) {
                                if (nextIndent == listIndent) {
                                    lineIndex++;
                                    continue;
                                } else if (nextIndent < listIndent) {
                                    break;
                                }
                            } else if (nextLine.indexOf(':') > 0 && nextIndent <= indent) {
                                break;
                            }
                        }
                        
                        lineIndex++;
                    }

                    for (Object item : newList) {
                        String itemStr = yaml.dump(item).trim();
                        result.append(" ".repeat(listIndent)).append("- ").append(itemStr).append("\n");
                    }
                }
                else {
                    result.append(line).append("\n");
                    lineIndex++;
                }
            } else {
                result.append(line).append("\n");
                lineIndex++;
            }
        }

        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object getValueAtPath(Map<String, Object> map, String path) {
        if (map == null || path == null) return null;
        
        String[] parts = path.split("\\.");
        Object current = map;
        
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
                if (current == null) return null;
            } else {
                return null;
            }
        }
        
        return current;
    }

    
    private static Yaml createYamlParser() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setSplitLines(true);
        return new Yaml(options);
    }
    
    private static Map<String, Object> loadConfigFromResource(InputStream is, Yaml yaml) {
        if (is == null) return null;
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            return convertToConcurrentMap(loaded);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static Map<String, Object> loadConfigFromFile(File file, Yaml yaml) {
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
            Object loaded = yaml.load(reader);
            return convertToConcurrentMap(loaded);
        } catch (Exception e) {
            return new ConcurrentHashMap<>();
        }
    }
    
    @SuppressWarnings("unchecked")
    private static Map<String, Object> convertToConcurrentMap(Object object) {
        if (object instanceof Map) {
            Map<Object, Object> originalMap = (Map<Object, Object>) object;
            Map<String, Object> concurrentMap = new ConcurrentHashMap<>();
            for (Map.Entry<Object, Object> entry : originalMap.entrySet()) {
                String key = entry.getKey().toString();
                Object value = entry.getValue();
                if (value instanceof Map) {
                    concurrentMap.put(key, convertToConcurrentMap(value));
                } else if (value instanceof List) {
                    concurrentMap.put(key, convertToConcurrentList((List<Object>) value));
                } else {
                    concurrentMap.put(key, value);
                }
            }
            return concurrentMap;
        }
        return new ConcurrentHashMap<>();
    }
    
    @SuppressWarnings("unchecked")
    private static List<Object> convertToConcurrentList(List<Object> originalList) {
        List<Object> concurrentList = new CopyOnWriteArrayList<>();
        for (Object item : originalList) {
            if (item instanceof Map) {
                concurrentList.add(convertToConcurrentMap(item));
            } else if (item instanceof List) {
                concurrentList.add(convertToConcurrentList((List<Object>) item));
            } else {
                concurrentList.add(item);
            }
        }
        return concurrentList;
    }
    
    private static Map<String, Object> mergeConfigs(Map<String, Object> defaultConfig, Map<String, Object> userConfig) {
        return mergeConfigs(defaultConfig, userConfig, "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeConfigs(Map<String, Object> defaultConfig, Map<String, Object> userConfig, String path) {
        Map<String, Object> merged = new ConcurrentHashMap<>();
        if (userConfig != null) {
            for (Map.Entry<String, Object> entry : userConfig.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                merged.put(key, deepCopyConcurrent(value));
            }
        }

        if (defaultConfig != null) {
            for (Map.Entry<String, Object> entry : defaultConfig.entrySet()) {
                String key = entry.getKey();
                String childPath = path.isEmpty() ? key : path + "." + key;
                Object defVal = entry.getValue();
                Object usrVal = merged.get(key);

                if (usrVal == null) {
                    merged.put(key, deepCopyConcurrent(defVal));
                    continue;
                }

                if (defVal instanceof Map && usrVal instanceof Map) {
                    merged.put(key, mergeConfigs((Map<String, Object>) defVal, (Map<String, Object>) usrVal, childPath));
                }
            }
        }

        return merged;
    }
    
    @SuppressWarnings("unchecked")
    private static Object deepCopyConcurrent(Object object) {
        if (object instanceof Map) {
            Map<Object, Object> originalMap = (Map<Object, Object>) object;
            Map<Object, Object> copiedMap = new ConcurrentHashMap<>();
            for (Map.Entry<Object, Object> entry : originalMap.entrySet()) {
                copiedMap.put(entry.getKey(), deepCopyConcurrent(entry.getValue()));
            }
            return copiedMap;
        } else if (object instanceof List) {
            List<Object> originalList = (List<Object>) object;
            List<Object> copiedList = new CopyOnWriteArrayList<>();
            for (Object item : originalList) {
                copiedList.add(deepCopyConcurrent(item));
            }
            return copiedList;
        } else {
            return object;
        }
    }
    
    private static void saveMergedConfig(File configFile, Map<String, Object> mergedConfig, JavaPlugin plugin, Yaml yaml) {
        File tempFile = new File(configFile.getParentFile(), "config_temp.yml");
        
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {

            updateVersionInConfig(mergedConfig, plugin);
            String header = extractHeader(configFile);
            String dumped = yaml.dump(mergedConfig);
            String existingSansHeader = readSansHeader(configFile);
            if (existingSansHeader != null && normalize(existingSansHeader).equals(normalize(dumped))) {
                return;
            }

            if (header != null && !header.isEmpty()) {
                writer.write(header);
                if (!header.endsWith("\n")) writer.write("\n");
            }
            writer.write(dumped);

            if (configFile.exists() && !configFile.delete()) {
                throw new IOException("Could not delete old config");
            }

            if (!tempFile.renameTo(configFile)) {
                throw new IOException("Could not rename temp config");
            }

            configCache.clear();

        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save updated config", ex);
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private static String extractHeader(File configFile) {
        if (configFile == null || !configFile.exists()) return "";
        try (FileInputStream fis = new FileInputStream(configFile)) {
            byte[] bytes = fis.readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\r?\n", -1);
            StringBuilder header = new StringBuilder();
            for (String line : lines) {
                if (line.trim().startsWith("#") || line.trim().isEmpty()) {
                    header.append(line).append("\n");
                } else {
                    break;
                }
            }
            return header.toString();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String readSansHeader(File configFile) {
        if (configFile == null || !configFile.exists()) return null;
        try (FileInputStream fis = new FileInputStream(configFile)) {
            byte[] bytes = fis.readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\r?\n", -1);
            StringBuilder body = new StringBuilder();
            boolean started = false;
            for (String line : lines) {
                if (!started) {
                    if (line.trim().startsWith("#") || line.trim().isEmpty()) {
                        continue;
                    }
                    started = true;
                }
                body.append(line).append("\n");
            }
            return body.toString();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace("\r", "\n").trim();
    }
    
    @SuppressWarnings("unchecked")
    private static void updateVersionInConfig(Map<String, Object> config, JavaPlugin plugin) {
        if (config.containsKey("generated-by-version")) {
            config.put("generated-by-version", "v" + plugin.getPluginMeta().getVersion());
        }
        
        for (Object value : config.values()) {
            if (value instanceof Map) {
                updateVersionInConfig((Map<String, Object>) value, plugin);
            }
        }
    }
    
    public static void updateConfigAsync(JavaPlugin plugin) {
        updateConfig(plugin);
    }
    
    public static void updateOption(FileConfiguration config, String path, Object defaultValue) {
        if (!config.contains(path)) {
            config.set(path, defaultValue);
        }
    }
    
    public static void reloadConfigSafely(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (configFile.exists()) {
            updateConfigParallel(plugin);
            SchedulerUtil.runTaskLater(plugin, () -> plugin.reloadConfig(), 2L);
        }
    }
    
    public static void clearCache() {
        configCache.clear();
        updateLocks.clear();
    }
    
    public static void shutdown() {
        clearCache();
    }
}