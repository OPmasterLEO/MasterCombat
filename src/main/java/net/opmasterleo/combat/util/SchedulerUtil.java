package net.opmasterleo.combat.util;

import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public class SchedulerUtil {
    
    private static final boolean IS_FOLIA = isFolia();

    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static void runTask(Plugin plugin, Runnable task) {
        if (IS_FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runTaskAsync(Plugin plugin, Runnable task) {
        if (IS_FOLIA) {
            try {
                Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
            } catch (Exception e) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public static void runTaskLater(Plugin plugin, Runnable task, long delay) {
        if (IS_FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delay);
            } catch (Exception e) {
                Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
    }

    public static void runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (IS_FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), delay, period);
            } catch (Exception e) {
                Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            }
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        }
    }

    public static void runTaskTimerAsync(Plugin plugin, Runnable task, long delay, long period) {
        if (IS_FOLIA) {
            try {
                Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), 
                        delay * 50, period * 50, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
            }
        } else {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        }
    }

    public static void runTaskForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (IS_FOLIA) {
            try {
                entity.getScheduler().run(plugin, scheduledTask -> task.run(), null);
            } catch (Exception e) {
                runTask(plugin, task);
            }
        } else {
            runTask(plugin, task);
        }
    }

    public static void runTaskForLocation(Plugin plugin, Location location, Runnable task) {
        if (IS_FOLIA) {
            try {
                Bukkit.getRegionScheduler().execute(plugin, location, task);
            } catch (Exception e) {
                runTask(plugin, task);
            }
        } else {
            runTask(plugin, task);
        }
    }
}
