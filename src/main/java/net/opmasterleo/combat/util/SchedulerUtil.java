package net.opmasterleo.combat.util;

import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class SchedulerUtil {
    private static final boolean IS_FOLIA = isFolia();
    private static final boolean IS_CANVAS = isCanvas();
    private static final boolean IS_PAPER = isPaper();
    private static final Set<BukkitTask> activeTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile boolean isShuttingDown = false;
    private static final int MAX_ACTIVE_TASKS = 2048;

    static {
        Bukkit.getScheduler().runTaskTimerAsynchronously(
            Bukkit.getPluginManager().getPlugin("MasterCombat"),
            () -> {
                activeTasks.removeIf(task -> task == null || task.isCancelled() || !task.isSync());
            },
            6000L, 6000L
        );
    }

    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isCanvas() {
        try {
            Class.forName("io.papermc.canvas.scheduler.CanvasScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isPaper() {
        try {
            Class.forName("io.papermc.paper.scheduler.PaperScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static void setShuttingDown(boolean shuttingDown) {
        isShuttingDown = shuttingDown;
    }

    public static void cancelAllTasks(Plugin plugin) {
        if (plugin == null) return;
        try {
            Bukkit.getScheduler().cancelTasks(plugin);
        } catch (Exception e) {}
        synchronized (activeTasks) {
            for (BukkitTask task : activeTasks) {
                try {
                    if (task != null && !task.isCancelled()) {
                        task.cancel();
                    }
                } catch (Exception e) {}
            }
            activeTasks.clear();
        }
    }

    public static BukkitTask runTask(Plugin plugin, Runnable task) {
        if (isShuttingDown || plugin == null || !plugin.isEnabled()) return null;
        BukkitTask bukkitTask = null;
        if (IS_FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().execute(plugin, task);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTask(plugin, task);
            }
        } else if (IS_CANVAS) {
            try {
                Bukkit.getGlobalRegionScheduler().execute(plugin, task);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTask(plugin, task);
            }
        } else if (IS_PAPER) {
            try {
                Bukkit.getScheduler().runTask(plugin, task);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTask(plugin, task);
            }
        } else {
            bukkitTask = Bukkit.getScheduler().runTask(plugin, task);
        }
        if (bukkitTask != null) trackTask(bukkitTask);
        return bukkitTask;
    }

    public static BukkitTask runTaskAsync(Plugin plugin, Runnable task) {
        if (isShuttingDown || plugin == null || !plugin.isEnabled()) return null;
        BukkitTask bukkitTask = null;
        if (IS_FOLIA) {
            try {
                Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        } else if (IS_CANVAS) {
            try {
                Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        } else if (IS_PAPER) {
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        } else {
            bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
        if (bukkitTask != null) trackTask(bukkitTask);
        return bukkitTask;
    }

    public static BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        if (isShuttingDown || plugin == null || !plugin.isEnabled()) return null;
        BukkitTask bukkitTask = null;
        if (IS_FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delay);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            }
        } else if (IS_CANVAS) {
            try {
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delay);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            }
        } else if (IS_PAPER) {
            try {
                Bukkit.getScheduler().runTaskLater(plugin, task, delay);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            }
        } else {
            bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
        if (bukkitTask != null) trackTask(bukkitTask);
        return bukkitTask;
    }

    public static BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (isShuttingDown || plugin == null || !plugin.isEnabled()) return null;
        BukkitTask bukkitTask = null;
        if (IS_FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), delay, period);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            }
        } else if (IS_CANVAS) {
            try {
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(), delay, period);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            }
        } else if (IS_PAPER) {
            try {
                Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            }
        } else {
            bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        }
        if (bukkitTask != null) trackTask(bukkitTask);
        return bukkitTask;
    }

    public static BukkitTask runTaskTimerAsync(Plugin plugin, Runnable task, long delay, long period) {
        if (isShuttingDown || plugin == null || !plugin.isEnabled()) return null;
        BukkitTask bukkitTask = null;
        if (IS_FOLIA) {
            try {
                Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(),
                        delay * 50, period * 50, TimeUnit.MILLISECONDS);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
            }
        } else if (IS_CANVAS) {
            try {
                Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> task.run(),
                        delay * 50, period * 50, TimeUnit.MILLISECONDS);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
            }
        } else if (IS_PAPER) {
            try {
                Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
            }
        } else {
            bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        }
        if (bukkitTask != null) trackTask(bukkitTask);
        return bukkitTask;
    }

    public static BukkitTask runTaskLaterAsync(Plugin plugin, Runnable task, long delay) {
        if (isShuttingDown || plugin == null || !plugin.isEnabled()) return null;
        BukkitTask bukkitTask = null;
        if (IS_FOLIA) {
            try {
                Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> task.run(),
                        delay * 50, TimeUnit.MILLISECONDS);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
            }
        } else if (IS_CANVAS) {
            try {
                Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> task.run(),
                        delay * 50, TimeUnit.MILLISECONDS);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
            }
        } else if (IS_PAPER) {
            try {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
                return null;
            } catch (Exception e) {
                bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
            }
        } else {
            bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
        }
        if (bukkitTask != null) trackTask(bukkitTask);
        return bukkitTask;
    }

    public static void runTaskForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (isShuttingDown || plugin == null || !plugin.isEnabled()) return;
        if (IS_FOLIA) {
            try {
                entity.getScheduler().run(plugin, scheduledTask -> task.run(), null);
            } catch (Exception e) {
                runTask(plugin, task);
            }
        } else if (IS_CANVAS) {
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
        if (isShuttingDown || plugin == null || !plugin.isEnabled()) return;
        if (IS_FOLIA) {
            try {
                Bukkit.getRegionScheduler().execute(plugin, location, task);
            } catch (Exception e) {
                runTask(plugin, task);
            }
        } else if (IS_CANVAS) {
            try {
                Bukkit.getRegionScheduler().execute(plugin, location, task);
            } catch (Exception e) {
                runTask(plugin, task);
            }
        } else {
            runTask(plugin, task);
        }
    }

    public static void removeTask(BukkitTask task) {
        if (task != null) {
            activeTasks.remove(task);
        }
    }

    public static Set<BukkitTask> getActiveTasks() {
        return activeTasks;
    }

    private static void trackTask(BukkitTask bukkitTask) {
        if (bukkitTask != null) {
            if (activeTasks.size() < MAX_ACTIVE_TASKS) {
                activeTasks.add(bukkitTask);
            } else {
                activeTasks.removeIf(task -> task == null || task.isCancelled());
                if (activeTasks.size() < MAX_ACTIVE_TASKS) {
                    activeTasks.add(bukkitTask);
                }
            }
        }
    }
}