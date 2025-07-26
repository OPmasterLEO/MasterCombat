package net.opmasterleo.combat.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SchedulerUtil {
    private static final boolean IS_FOLIA = checkClass("io.papermc.paper.threadedregions.RegionizedServer");
    private static final boolean IS_CANVAS = checkClass("io.papermc.canvas.scheduler.CanvasScheduler");
    private static final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private static final Set<BukkitTask> activeTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int MAX_ACTIVE_TASKS = 1024;
    private static final long CLEANUP_INTERVAL = 6000L; // 5 minutes

    static {
        scheduleCleanupTask();
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    private static boolean checkClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void scheduleCleanupTask() {
        runTaskTimerAsync(
            getPlugin(),
            () -> activeTasks.removeIf(task -> task == null || task.isCancelled()),
            CLEANUP_INTERVAL, CLEANUP_INTERVAL
        );
    }

    private static Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("MasterCombat");
    }

    public static void setShuttingDown(boolean shuttingDown) {
        isShuttingDown.set(shuttingDown);
    }

    public static void cancelAllTasks(Plugin plugin) {
        if (plugin == null) return;
        Bukkit.getScheduler().cancelTasks(plugin);
        activeTasks.removeIf(task -> {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
            return true;
        });
    }

    // Maintain old method signatures for compatibility
    public static BukkitTask runTask(Plugin plugin, Runnable task) {
        if (shouldSkip(plugin)) return null;
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            return null;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTask(plugin, task);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskAsync(Plugin plugin, Runnable task) {
        if (shouldSkip(plugin)) return null;
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
            return null;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        if (shouldSkip(plugin)) return null;
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delay);
            return null;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskLaterAsync(Plugin plugin, Runnable task, long delay) {
        if (shouldSkip(plugin)) return null;
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delay * 50, TimeUnit.MILLISECONDS);
            return null;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (shouldSkip(plugin)) return null;
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), delay, period);
            return null;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskTimerAsync(Plugin plugin, Runnable task, long delay, long period) {
        if (shouldSkip(plugin)) return null;
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getAsyncScheduler().runAtFixedRate(plugin, 
                t -> task.run(), delay * 50, period * 50, TimeUnit.MILLISECONDS);
            return null;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static void runTaskForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (shouldSkip(plugin)) return;
        
        if (IS_FOLIA || IS_CANVAS) {
            entity.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            runTask(plugin, task);
        }
    }

    public static void runTaskForLocation(Plugin plugin, Location location, Runnable task) {
        if (shouldSkip(plugin)) return;
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getRegionScheduler().execute(plugin, location, task);
        } else {
            runTask(plugin, task);
        }
    }

    private static boolean shouldSkip(Plugin plugin) {
        return isShuttingDown.get() || plugin == null || !plugin.isEnabled();
    }

    private static void trackTask(BukkitTask task) {
        if (task == null) return;
        
        if (activeTasks.size() >= MAX_ACTIVE_TASKS) {
            activeTasks.removeIf(t -> t == null || t.isCancelled());
        }
        
        if (activeTasks.size() < MAX_ACTIVE_TASKS) {
            activeTasks.add(task);
        }
    }

    public static void removeTask(BukkitTask task) {
        if (task != null) {
            activeTasks.remove(task);
        }
    }
}