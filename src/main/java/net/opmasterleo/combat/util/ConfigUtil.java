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
    
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeConfigs(Map<String, Object> defaultConfig, Map<String, Object> userConfig) {
        Map<String, Object> merged = new ConcurrentHashMap<>();
        
        if (userConfig != null) {
            for (Map.Entry<String, Object> entry : userConfig.entrySet()) {
                merged.put(entry.getKey(), deepCopyConcurrent(entry.getValue()));
            }
        }
        
        if (defaultConfig != null) {
            for (Map.Entry<String, Object> entry : defaultConfig.entrySet()) {
                String key = entry.getKey();
                Object defaultValue = entry.getValue();
                Object userValue = merged.get(key);
                
                if (userValue == null) {
                    merged.put(key, deepCopyConcurrent(defaultValue));
                } else if (defaultValue instanceof Map && userValue instanceof Map) {
                    Map<String, Object> defaultMap = (Map<String, Object>) defaultValue;
                    Map<String, Object> userMap = (Map<String, Object>) userValue;
                    merged.put(key, mergeConfigs(defaultMap, userMap));
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
            yaml.dump(mergedConfig, writer);
            
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