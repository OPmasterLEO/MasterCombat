package net.opmasterleo.combat.util;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SchedulerUtil {
    private static final boolean IS_FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");
    private static final boolean IS_CANVAS = classExists("io.papermc.canvas.scheduler.CanvasScheduler");
    private static final boolean IS_ARCLIGHT = classExists("io.izzel.arclight.common.mod.ArclightMod");
    private static final boolean IS_PAPER = classExists("com.destroystokyo.paper.PaperConfig");
    private static final boolean IS_LEGACY_PAPER = !IS_PAPER && classExists("org.github.paperspigot.PaperSpigotConfig");
    private static final boolean IS_LEGACY_SPIGOT = !IS_PAPER && !IS_LEGACY_PAPER && classExists("org.spigotmc.SpigotConfig");
    private static final boolean IS_MODERN_SPIGOT = !IS_PAPER && !IS_LEGACY_PAPER && IS_LEGACY_SPIGOT && classExists("org.spigotmc.AsyncCatcher");
    private static final boolean SUPPORTS_ASYNC = IS_PAPER || IS_MODERN_SPIGOT || IS_LEGACY_PAPER;
    private static final boolean PAPER_ENTITY_SCHEDULER = (IS_PAPER || IS_LEGACY_PAPER) && methodExists("org.bukkit.entity.Entity", "getScheduler");
    
    private static final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();
    private static final int MAX_WORKER_THREADS = Math.max(2, Math.min(AVAILABLE_PROCESSORS * 2, 16));
    private static final Set<BukkitTask> activeTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final long CLEANUP_INTERVAL = 6000L;
    private static final ThreadPoolManager THREAD_POOL = new ThreadPoolManager();
    
    private static final Map<String, AtomicLong> regionTaskCounters = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> entityTaskCounters = new ConcurrentHashMap<>();
    private static final AtomicInteger activeAsyncTasks = new AtomicInteger(0);
    private static final int MAX_CONCURRENT_ASYNC_TASKS = Math.max(4, AVAILABLE_PROCESSORS);
    private static final Map<String, Long> lastRegionExecution = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastEntityExecution = new ConcurrentHashMap<>();
    
    static {
        scheduleCleanupTask();
    }
    
    private static class ThreadPoolManager {
        private final java.util.concurrent.ThreadPoolExecutor asyncPool;
        private final java.util.concurrent.atomic.AtomicInteger threadNumber = new java.util.concurrent.atomic.AtomicInteger(1);
        
        ThreadPoolManager() {
            this.asyncPool = new java.util.concurrent.ThreadPoolExecutor(
                Math.max(2, MAX_WORKER_THREADS / 2),
                MAX_WORKER_THREADS,
                30L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(1000),
                r -> {
                    Thread thread = new Thread(r, "MasterCombat-Worker-" + threadNumber.getAndIncrement());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                },
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
            );
            this.asyncPool.allowCoreThreadTimeOut(true);
        }
        
        void execute(Runnable task) {
            if (!isShuttingDown.get()) {
                asyncPool.execute(task);
            }
        }
        
        void shutdown() {
            asyncPool.shutdown();
            try {
                if (!asyncPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
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
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    private static void scheduleCleanupTask() {
        runTaskTimerAsync(
            getPlugin(),
            () -> {
                activeTasks.removeIf(task -> task == null || task.isCancelled());
                
                int activeTaskCount = activeTasks.size();
                long currentTime = System.currentTimeMillis();
                
                regionTaskCounters.entrySet().removeIf(entry -> 
                    currentTime - entry.getValue().get() > 300000);
                
                entityTaskCounters.entrySet().removeIf(entry -> 
                    currentTime - entry.getValue().get() > 300000);
                
                lastRegionExecution.entrySet().removeIf(entry -> 
                    currentTime - entry.getValue() > 600000);
                
                lastEntityExecution.entrySet().removeIf(entry -> 
                    currentTime - entry.getValue() > 600000);
                
                if (activeTaskCount > 1000) {
                    java.util.logging.Logger logger = Bukkit.getLogger();
                    if (logger.isLoggable(java.util.logging.Level.WARNING)) {
                        logger.log(java.util.logging.Level.WARNING, "MasterCombat: High task count detected: {0}", activeTaskCount);
                    }
                }
            },
            CLEANUP_INTERVAL, CLEANUP_INTERVAL
        );
    }

    private static Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("MasterCombat");
    }

    public static void setShuttingDown(boolean shuttingDown) {
        isShuttingDown.set(shuttingDown);
        if (shuttingDown) {
            THREAD_POOL.shutdown();
        }
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

    private static Runnable wrapTask(Runnable task, Plugin plugin, String context) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                if (plugin.getLogger().isLoggable(java.util.logging.Level.SEVERE)) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in {0} task", new Object[]{context, e});
                }
            }
        };
    }

    private static Runnable wrapArclightTask(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                Plugin plugin = getPlugin();
                if (plugin != null && plugin.getLogger().isLoggable(java.util.logging.Level.WARNING)) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Arclight task error", e);
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
                    if (plugin.getLogger().isLoggable(java.util.logging.Level.WARNING)) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Arclight async task error", e);
                    }
                }
            }
        };
    }

    private static boolean shouldRunSync() {
        return Bukkit.isPrimaryThread() || (!IS_FOLIA && !IS_CANVAS);
    }

    private static String getRegionKey(Location location) {
        return location.getWorld().getName() + ":" + (location.getBlockX() >> 9) + ":" + (location.getBlockZ() >> 9);
    }

    private static String getEntityKey(Entity entity) {
        return entity.getUniqueId().toString();
    }

    private static boolean shouldThrottleRegion(Location location, long minInterval) {
        String regionKey = getRegionKey(location);
        long currentTime = System.currentTimeMillis();
        Long lastExecuted = lastRegionExecution.get(regionKey);
        
        if (lastExecuted != null && currentTime - lastExecuted < minInterval) {
            return true;
        }
        
        lastRegionExecution.put(regionKey, currentTime);
        return false;
    }

    private static boolean shouldThrottleEntity(Entity entity, long minInterval) {
        String entityKey = getEntityKey(entity);
        long currentTime = System.currentTimeMillis();
        Long lastExecuted = lastEntityExecution.get(entityKey);
        
        if (lastExecuted != null && currentTime - lastExecuted < minInterval) {
            return true;
        }
        
        lastEntityExecution.put(entityKey, currentTime);
        return false;
    }

    private static void incrementRegionCounter(Location location) {
        String regionKey = getRegionKey(location);
        regionTaskCounters.computeIfAbsent(regionKey, k -> new AtomicLong(0)).incrementAndGet();
    }

    private static void incrementEntityCounter(Entity entity) {
        String entityKey = getEntityKey(entity);
        entityTaskCounters.computeIfAbsent(entityKey, k -> new AtomicLong(0)).incrementAndGet();
    }

    private static boolean canRunAsync() {
        return activeAsyncTasks.get() < MAX_CONCURRENT_ASYNC_TASKS;
    }

    public static BukkitTask runTask(Plugin plugin, Runnable task) {
        if (shouldSkip(plugin)) return null;
        
        Runnable wrapped = wrapTask(task, plugin, "sync");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, wrapped);
            return null;
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTask(plugin, wrapArclightTask(wrapped));
            trackTask(bukkitTask);
            return bukkitTask;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTask(plugin, wrapped);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskAsync(Plugin plugin, Runnable task) {
        if (shouldSkip(plugin)) return null;
        
        Runnable wrapped = wrapTask(task, plugin, "async");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> wrapped.run());
            return null;
        } else if (!SUPPORTS_ASYNC) {
            if (canRunAsync()) {
                activeAsyncTasks.incrementAndGet();
                THREAD_POOL.execute(() -> {
                    try {
                        wrapped.run();
                    } finally {
                        activeAsyncTasks.decrementAndGet();
                    }
                });
            }
            return null;
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, wrapArclightAsyncTask(wrapped));
            trackTask(bukkitTask);
            return bukkitTask;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, wrapped);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        if (shouldSkip(plugin)) return null;
        
        Runnable wrapped = wrapTask(task, plugin, "delayed");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> wrapped.run(), delay);
            return null;
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, wrapArclightTask(wrapped), delay);
            trackTask(bukkitTask);
            return bukkitTask;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, wrapped, delay);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskLaterAsync(Plugin plugin, Runnable task, long delay) {
        if (shouldSkip(plugin)) return null;
        
        Runnable wrapped = wrapTask(task, plugin, "async delayed");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, t -> wrapped.run(), delay * 50, TimeUnit.MILLISECONDS);
            return null;
        } else if (!SUPPORTS_ASYNC) {
            java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MasterCombat-Delayed-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });
            scheduler.schedule(() -> {
                try {
                    if (canRunAsync()) {
                        activeAsyncTasks.incrementAndGet();
                        THREAD_POOL.execute(() -> {
                            try {
                                wrapped.run();
                            } finally {
                                activeAsyncTasks.decrementAndGet();
                            }
                        });
                    }
                } finally {
                    scheduler.shutdown();
                }
            }, delay * 50, TimeUnit.MILLISECONDS);
            return null;
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapArclightAsyncTask(wrapped), delay);
            trackTask(bukkitTask);
            return bukkitTask;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, wrapped, delay);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (shouldSkip(plugin)) return null;
        
        Runnable wrapped = wrapTask(task, plugin, "timer");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> wrapped.run(), delay, period);
            return null;
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, wrapArclightTask(wrapped), delay, period);
            trackTask(bukkitTask);
            return bukkitTask;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, wrapped, delay, period);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static BukkitTask runTaskTimerAsync(Plugin plugin, Runnable task, long delay, long period) {
        if (shouldSkip(plugin)) return null;
        
        Runnable wrapped = wrapTask(task, plugin, "async timer");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getAsyncScheduler().runAtFixedRate(plugin, 
                t -> wrapped.run(), delay * 50, period * 50, TimeUnit.MILLISECONDS);
            return null;
        } else if (!SUPPORTS_ASYNC) {
            java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MasterCombat-Timer-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(() -> {
                if (!isShuttingDown.get()) {
                    try {
                        if (canRunAsync()) {
                            activeAsyncTasks.incrementAndGet();
                            THREAD_POOL.execute(() -> {
                                try {
                                    wrapped.run();
                                } finally {
                                    activeAsyncTasks.decrementAndGet();
                                }
                            });
                        }
                    } catch (Exception e) {
                        scheduler.shutdown();
                    }
                } else {
                    scheduler.shutdown();
                }
            }, delay * 50, period * 50, TimeUnit.MILLISECONDS);
            return null;
        } else if (IS_ARCLIGHT) {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, wrapArclightAsyncTask(wrapped), delay, period);
            trackTask(bukkitTask);
            return bukkitTask;
        } else {
            BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, wrapped, delay, period);
            trackTask(bukkitTask);
            return bukkitTask;
        }
    }

    public static void runEntityTask(Plugin plugin, Entity entity, Runnable task) {
        runEntityTaskLater(plugin, entity, task, 0);
    }

    public static void runEntityTaskLater(Plugin plugin, Entity entity, Runnable task, long delay) {
        if (shouldSkip(plugin)) return;
        
        Runnable wrapped = wrapTask(task, plugin, "entity");
        
        if (IS_FOLIA || IS_CANVAS) {
            entity.getScheduler().runDelayed(plugin, t -> wrapped.run(), null, delay);
        } else if (PAPER_ENTITY_SCHEDULER) {
            try {
                entity.getScheduler().runDelayed(plugin, t -> wrapped.run(), null, delay);
            } catch (Exception e) {
                runTaskLater(plugin, wrapped, delay);
            }
        } else if (IS_ARCLIGHT) {
            runTaskLater(plugin, wrapArclightTask(wrapped), delay);
        } else {
            runTaskLater(plugin, wrapped, delay);
        }
    }

    public static void runEntityTaskTimer(Plugin plugin, Entity entity, Runnable task, long delay, long period) {
        runEntityTaskTimer(plugin, entity, task, delay, period, 0);
    }

    public static void runEntityTaskTimer(Plugin plugin, Entity entity, Runnable task, long delay, long period, long minInterval) {
        if (shouldSkip(plugin)) return;
        
        if (minInterval > 0 && shouldThrottleEntity(entity, minInterval)) {
            return;
        }
        
        incrementEntityCounter(entity);
        Runnable wrapped = wrapTask(task, plugin, "entity");
        
        if (IS_FOLIA || IS_CANVAS) {
            entity.getScheduler().runAtFixedRate(plugin, t -> wrapped.run(), null, delay, period);
        } else if (PAPER_ENTITY_SCHEDULER) {
            try {
                entity.getScheduler().runAtFixedRate(plugin, t -> wrapped.run(), null, delay, period);
            } catch (Exception e) {
                runTaskTimer(plugin, wrapped, delay, period);
            }
        } else if (IS_ARCLIGHT) {
            runTaskTimer(plugin, wrapArclightTask(wrapped), delay, period);
        } else {
            runTaskTimer(plugin, wrapped, delay, period);
        }
    }

    public static void runRegionTask(Plugin plugin, Location location, Runnable task) {
        runRegionTaskLater(plugin, location, task, 0);
    }

    public static void runRegionTaskLater(Plugin plugin, Location location, Runnable task, long delay) {
        if (shouldSkip(plugin)) return;
        
        Runnable wrapped = wrapTask(task, plugin, "region");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getRegionScheduler().runDelayed(plugin, location, t -> wrapped.run(), delay);
        } else if (IS_ARCLIGHT) {
            runTaskLater(plugin, wrapArclightTask(wrapped), delay);
        } else {
            runTaskLater(plugin, wrapped, delay);
        }
    }

    public static void runRegionTaskTimer(Plugin plugin, Location location, Runnable task, long delay, long period) {
        runRegionTaskTimer(plugin, location, task, delay, period, 0);
    }

    public static void runRegionTaskTimer(Plugin plugin, Location location, Runnable task, long delay, long period, long minInterval) {
        if (shouldSkip(plugin)) return;
        
        if (minInterval > 0 && shouldThrottleRegion(location, minInterval)) {
            return;
        }
        
        incrementRegionCounter(location);
        Runnable wrapped = wrapTask(task, plugin, "region");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, t -> wrapped.run(), delay, period);
        } else if (IS_ARCLIGHT) {
            runTaskTimer(plugin, wrapArclightTask(wrapped), delay, period);
        } else {
            runTaskTimer(plugin, wrapped, delay, period);
        }
    }

    public static void runWorldTask(Plugin plugin, World world, Runnable task) {
        runWorldTaskLater(plugin, world, task, 0);
    }

    public static void runWorldTaskLater(Plugin plugin, World world, Runnable task, long delay) {
        if (shouldSkip(plugin)) return;
        
        Runnable wrapped = wrapTask(task, plugin, "world");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> wrapped.run(), delay);
        } else if (IS_ARCLIGHT) {
            runTaskLater(plugin, wrapArclightTask(wrapped), delay);
        } else {
            runTaskLater(plugin, wrapped, delay);
        }
    }

    public static void runWorldTaskTimer(Plugin plugin, World world, Runnable task, long delay, long period) {
        if (shouldSkip(plugin)) return;
        
        Runnable wrapped = wrapTask(task, plugin, "world");
        
        if (IS_FOLIA || IS_CANVAS) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> wrapped.run(), delay, period);
        } else if (IS_ARCLIGHT) {
            runTaskTimer(plugin, wrapArclightTask(wrapped), delay, period);
        } else {
            runTaskTimer(plugin, wrapped, delay, period);
        }
    }

    public static void runBatchEntityTasks(Plugin plugin, Collection<Entity> entities, Consumer<Entity> task) {
        if (shouldSkip(plugin) || entities == null || entities.isEmpty()) return;
        
        if (IS_FOLIA || IS_CANVAS) {
            Map<String, Set<Entity>> regionEntities = new HashMap<>();
            
            for (Entity entity : entities) {
                if (entity == null || !entity.isValid()) continue;
                
                String regionKey = getRegionKey(entity.getLocation());
                regionEntities.computeIfAbsent(regionKey, k -> new HashSet<>()).add(entity);
            }
            
            for (Set<Entity> regionGroup : regionEntities.values()) {
                if (regionGroup.isEmpty()) continue;
                
                Entity firstEntity = regionGroup.iterator().next();
                runEntityTask(plugin, firstEntity, () -> {
                    for (Entity entity : regionGroup) {
                        if (entity.isValid()) {
                            try {
                                task.accept(entity);
                            } catch (Exception e) {
                                if (plugin.getLogger().isLoggable(java.util.logging.Level.WARNING)) {
                                plugin.getLogger().log(java.util.logging.Level.WARNING, "Error in batch entity task", e);
                            }
                            }
                        }
                    }
                });
            }
        } else {
            for (Entity entity : entities) {
                if (entity != null && entity.isValid()) {
                    runEntityTask(plugin, entity, () -> task.accept(entity));
                }
            }
        }
    }

    public static void runBatchRegionTasks(Plugin plugin, Collection<Location> locations, Consumer<Location> task) {
        if (shouldSkip(plugin) || locations == null || locations.isEmpty()) return;
        
        if (IS_FOLIA || IS_CANVAS) {
            Map<String, Set<Location>> regionGroups = new HashMap<>();
            
            for (Location location : locations) {
                if (location == null || location.getWorld() == null) continue;
                
                String regionKey = getRegionKey(location);
                regionGroups.computeIfAbsent(regionKey, k -> new HashSet<>()).add(location);
            }
            
            for (Set<Location> regionGroup : regionGroups.values()) {
                if (regionGroup.isEmpty()) continue;
                
                Location firstLocation = regionGroup.iterator().next();
                runRegionTask(plugin, firstLocation, () -> {
                    for (Location location : regionGroup) {
                        try {
                            task.accept(location);
                        } catch (Exception e) {
                            if (plugin.getLogger().isLoggable(java.util.logging.Level.WARNING)) {
                                plugin.getLogger().log(java.util.logging.Level.WARNING, "Error in batch region task", e);
                            }
                        }
                    }
                });
            }
        } else {
            for (Location location : locations) {
                if (location != null && location.getWorld() != null) {
                    runRegionTask(plugin, location, () -> task.accept(location));
                }
            }
        }
    }

    public static void runBatchWorldTasks(Plugin plugin, Collection<World> worlds, Consumer<World> task) {
        if (shouldSkip(plugin) || worlds == null || worlds.isEmpty()) return;
        
        for (World world : worlds) {
            if (world != null) {
                runWorldTask(plugin, world, () -> task.accept(world));
            }
        }
    }

    public static void runContextAwareTask(Plugin plugin, Runnable task) {
        if (shouldSkip(plugin)) return;
        
        Runnable wrapped = wrapTask(task, plugin, "context-aware");
        
        if (shouldRunSync()) {
            runTask(plugin, wrapped);
        } else {
            runTaskAsync(plugin, wrapped);
        }
    }

    public static void runContextAwareTaskLater(Plugin plugin, Runnable task, long delay) {
        if (shouldSkip(plugin)) return;
        
        Runnable wrapped = wrapTask(task, plugin, "context-aware delayed");
        
        if (shouldRunSync()) {
            runTaskLater(plugin, wrapped, delay);
        } else {
            runTaskLaterAsync(plugin, wrapped, delay);
        }
    }

    public static void runContextAwareTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (shouldSkip(plugin)) return;
        
        Runnable wrapped = wrapTask(task, plugin, "context-aware timer");
        
        if (shouldRunSync()) {
            runTaskTimer(plugin, wrapped, delay, period);
        } else {
            runTaskTimerAsync(plugin, wrapped, delay, period);
        }
    }

    public static void runFoliaAsyncRegionTask(Plugin plugin, Location location, Runnable task) {
        if (shouldSkip(plugin)) return;
        
        Runnable wrapped = wrapTask(task, plugin, "folia async region");
        
        if (IS_FOLIA) {
            Bukkit.getRegionScheduler().execute(plugin, location, wrapped);
        } else {
            runTaskAsync(plugin, wrapped);
        }
    }

    public static void removeTask(BukkitTask task) {
        if (task != null) {
            activeTasks.remove(task);
        }
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
                    if (plugin.getLogger().isLoggable(java.util.logging.Level.WARNING)) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Error processing player {0}", new Object[]{player.getName(), e});
                    }
                }
            }
        }, executor);
    }

    public static <T> void batchProcessPlayersOptimized(JavaPlugin plugin, Executor executor,
                                        Player[] players, 
                                        Function<Player, T> processor,
                                        Consumer<Collection<PlayerProcessResult<T>>> applier) {
        if (isShuttingDown.get() || players.length == 0) return;
        
        CompletableFuture.runAsync(() -> {
            Collection<PlayerProcessResult<T>> results = new HashSet<>();
            
            for (Player player : players) {
                if (player == null || !player.isOnline()) continue;
                
                try {
                    T result = processor.apply(player);
                    results.add(new PlayerProcessResult<>(player, result));
                } catch (Exception e) {
                    if (plugin.getLogger().isLoggable(java.util.logging.Level.WARNING)) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Error processing player in batch operation", e);
                    }
                }
            }
            
            runTask(plugin, () -> applier.accept(results));
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