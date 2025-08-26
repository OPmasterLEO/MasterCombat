package net.opmasterleo.combat.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SchedulerUtil {
    private static final boolean IS_FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");
    private static final boolean IS_CANVAS = classExists("io.papermc.canvas.scheduler.CanvasScheduler");
    private static final boolean IS_ARCLIGHT = classExists("io.izzel.arclight.common.mod.ArclightMod");
    private static final boolean IS_PAPER = classExists("com.destroystokyo.paper.PaperConfig");
    private static final boolean PAPER_ENTITY_SCHEDULER = IS_PAPER && methodExists("org.bukkit.entity.Entity", "getScheduler");
    
    private static final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private static final Set<BukkitTask> activeTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final long CLEANUP_INTERVAL = 6000L;
    
    static {
        scheduleCleanupTask();
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean methodExists(String className, String methodName) {
        try {
            Class.forName(className).getMethod(methodName);
            return true;
        } catch (Exception e) {
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

    private static boolean shouldSkip(Plugin plugin) {
        return isShuttingDown.get() || plugin == null || !plugin.isEnabled();
    }

    private static void trackTask(BukkitTask task) {
        if (task != null) activeTasks.add(task);
    }

    // Arclight-specific task wrappers
    private static Runnable wrapArclightTask(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                Plugin plugin = getPlugin();
                if (plugin != null) {
                    plugin.getLogger().warning("Arclight task error: " + e.getMessage());
                }
            }
        };
    }

    private static Runnable wrapArclightAsyncTask(Runnable task) {
        return () -> {
            try {
                ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
                try {
                    Plugin plugin = getPlugin();
                    if (plugin != null) {
                        Thread.currentThread().setContextClassLoader(plugin.getClass().getClassLoader());
                    }
                    task.run();
                } finally {
                    Thread.currentThread().setContextClassLoader(originalClassLoader);
                }
            } catch (Exception e) {
                Plugin plugin = getPlugin();
                if (plugin != null) {
                    plugin.getLogger().warning("Arclight async task error: " + e.getMessage());
                }
            }
        };
    }

    public static BukkitTask runTask(Plugin plugin, Runnable task) {
        if (shouldSkip(plugin)) return null;
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
            return null;
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTask(plugin, wrapArclightTask(task));
            trackTask(bukkitTask);
            return bukkitTask;
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
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, wrapArclightAsyncTask(task));
            trackTask(bukkitTask);
            return bukkitTask;
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
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, wrapArclightTask(task), delay);
            trackTask(bukkitTask);
            return bukkitTask;
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
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapArclightAsyncTask(task), delay);
            trackTask(bukkitTask);
            return bukkitTask;
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
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, wrapArclightTask(task), delay, period);
            trackTask(bukkitTask);
            return bukkitTask;
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
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, wrapArclightAsyncTask(task), delay, period);
            trackTask(bukkitTask);
            return bukkitTask;
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
        } else if (PAPER_ENTITY_SCHEDULER) {
            try {
                entity.getScheduler().run(plugin, t -> task.run(), null);
            } catch (Exception e) {
                runTask(plugin, task);
            }
        } else if (IS_ARCLIGHT) {
            runTask(plugin, wrapArclightTask(task));
        } else {
            runTask(plugin, task);
        }
    }

    public static void runTaskForLocation(Plugin plugin, Location location, Runnable task) {
        if (shouldSkip(plugin)) return;
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getRegionScheduler().execute(plugin, location, task);
        } else if (IS_ARCLIGHT) {
            runTask(plugin, wrapArclightTask(task));
        } else {
            runTask(plugin, task);
        }
    }

    public static void runTaskBatch(Plugin plugin, Collection<Runnable> tasks) {
        if (shouldSkip(plugin) || tasks == null || tasks.isEmpty()) return;
        
        if (tasks.size() == 1) {
            runTaskAsync(plugin, tasks.iterator().next());
        } else {
            runTaskAsync(plugin, () -> tasks.forEach(task -> {
                try {
                    task.run();
                } catch (Exception e) {
                    plugin.getLogger().warning("Batch task error: " + e.getMessage());
                }
            }));
        }
    }

    public static void removeTask(BukkitTask task) {
        if (task != null) {
            activeTasks.remove(task);
        }
    }

    public static void runEntityTask(Plugin plugin, Entity entity, Runnable task) {
        if (isFolia()) {
            try {
                Class<?> entitySchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                Object entityScheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Method runMethod = entitySchedulerClass.getMethod("run", Plugin.class, Consumer.class);
                
                runMethod.invoke(entityScheduler, plugin, (Consumer<BukkitTask>) (bukkitTask) -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error in entity task: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to schedule entity task with Folia: " + e.getMessage());
            }
        }
        
        runTask(plugin, task);
    }

    public static void runRegionTask(Plugin plugin, Location location, Runnable task) {
        if (isFolia()) {
            try {
                Class<?> regionSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
                Object regionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
                Method runMethod = regionSchedulerClass.getMethod("run", Plugin.class, Location.class, Consumer.class);
                
                runMethod.invoke(regionScheduler, plugin, location, (Consumer<BukkitTask>) (bukkitTask) -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error in region task: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to schedule region task with Folia: " + e.getMessage());
            }
        }
        
        runTask(plugin, task);
    }

    public static void runWorldTask(Plugin plugin, World world, Runnable task) {
        if (isFolia()) {
            try {
                Class<?> globalRegionSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                Object globalRegionScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
                Method runMethod = globalRegionSchedulerClass.getMethod("run", Plugin.class, Consumer.class);
                
                runMethod.invoke(globalRegionScheduler, plugin, (Consumer<BukkitTask>) (bukkitTask) -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error in world task: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to schedule world task with Folia: " + e.getMessage());
            }
        }
        
        runTask(plugin, task);
    }
    
    public static <T> void supplyAsync(JavaPlugin plugin, Executor executor, 
                                    Supplier<T> supplier, Consumer<T> consumer) {
        if (isShuttingDown.get()) return;
        
        CompletableFuture.supplyAsync(supplier, executor)
            .thenAccept(result -> runTask(plugin, () -> consumer.accept(result)));
    }

    public static <T> void batchProcessPlayers(JavaPlugin plugin, Executor executor,
                                        Player[] players, 
                                        Function<Player, T> processor,
                                        Consumer<PlayerProcessResult<T>> applier) {
        if (isShuttingDown.get() || players.length == 0) return;
        
        CompletableFuture.runAsync(() -> {
            for (Player player : players) {
                if (player == null || !player.isOnline()) continue;
                
                try {
                    T result = processor.apply(player);
                    runEntityTask(plugin, player, () -> applier.accept(new PlayerProcessResult<>(player, result)));
                } catch (Exception e) {
                    plugin.getLogger().warning("Error processing player " + player.getName() + ": " + e.getMessage());
                }
            }
        }, executor);
    }

    public static class PlayerProcessResult<T> {
        private final Player player;
        private final T result;
        
        public PlayerProcessResult(Player player, T result) {
            this.player = player;
            this.result = result;
        }
        
        public Player getPlayer() {
            return player;
        }
        
        public T getResult() {
            return result;
        }
    }
}