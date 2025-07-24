package net.opmasterleo.combat.util;

import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

public class ConfigUtil {
    private static final Yaml yaml = new Yaml();
    private static final String GENERATED_BY_VERSION_KEY = "generated-by-version";

    public static void updateConfig(JavaPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;

        Map<String, Object> defaultConfig;
        try (InputStream is = plugin.getResource("config.yml")) {
            if (is == null) {
                plugin.getLogger().warning("Default config.yml not found in plugin JAR! Config update skipped.");
                return;
            }
            Object loaded = yaml.load(is);
            if (loaded instanceof Map) {
                defaultConfig = new LinkedHashMap<>(suppressUncheckedCastToMap(loaded));
            } else if (loaded == null) {
                defaultConfig = new LinkedHashMap<>();
            } else {
                plugin.getLogger().warning("Unexpected type for default config.yml root: " + loaded.getClass());
                defaultConfig = new LinkedHashMap<>();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not load default config.yml from JAR", e);
            return;
        }

        Map<String, Object> userConfig;
        try (InputStream is = new FileInputStream(configFile)) {
            Object loaded = yaml.load(is);
            if (loaded instanceof Map) {
                userConfig = new LinkedHashMap<>(suppressUncheckedCastToMap(loaded));
            } else if (loaded == null) {
                userConfig = new LinkedHashMap<>();
            } else {
                plugin.getLogger().warning("Unexpected type for user config.yml root: " + loaded.getClass());
                userConfig = new LinkedHashMap<>();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not load user config.yml", e);
            return;
        }

        boolean changed = mergeMissingKeys(defaultConfig, userConfig, plugin);

        if (changed) {
            String version = plugin.getPluginMeta().getVersion();
            userConfig.put(GENERATED_BY_VERSION_KEY, "v" + version);

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setIndent(2);
            options.setPrettyFlow(true);
            Yaml dumpYaml = new Yaml(options);

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
                dumpYaml.dump(userConfig, writer);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not save updated config.yml", e);
            }
        }
    }

    public static void updateConfigAsync(JavaPlugin plugin) {
        net.opmasterleo.combat.util.SchedulerUtil.runTaskAsync(plugin, () -> updateConfig(plugin));
    }

    private static boolean mergeMissingKeys(Map<String, Object> source, Map<String, Object> target, JavaPlugin plugin) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!target.containsKey(key)) {
                target.put(key, value);
                changed = true;
            } else if (value instanceof Map) {
                Object targetValue = target.get(key);
                if (targetValue instanceof Map) {
                    if (mergeMissingKeys(
                            suppressUncheckedCastToMap(value),
                            suppressUncheckedCastToMap(targetValue),
                            plugin
                        )) {
                        changed = true;
                    }
                } else {
                    plugin.getLogger().warning("Type mismatch for key '" + key + "': default is Map, user is " + (targetValue == null ? "null" : targetValue.getClass()));
                }
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> suppressUncheckedCastToMap(Object obj) {
        return (Map<String, Object>) obj;
    }
}
