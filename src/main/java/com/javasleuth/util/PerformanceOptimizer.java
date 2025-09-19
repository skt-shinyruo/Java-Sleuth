package com.javasleuth.util;

import com.javasleuth.config.ProductionConfig;
import java.lang.management.ManagementFactory;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.*;

public class PerformanceOptimizer implements PerformanceOptimizerMBean {
    private static PerformanceOptimizer instance;
    private final ProductionConfig config;

    private final ExecutorService commandExecutor;
    private final ScheduledExecutorService maintenanceExecutor;

    // Enhanced multi-tier caching
    private final ConcurrentHashMap<String, CacheEntry> resultCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry> longTermCache = new ConcurrentHashMap<>();

    // Performance metrics
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong slowOperations = new AtomicLong(0);

    // Thresholds
    private static final long DEFAULT_CACHE_TTL_MS = 5000;
    private static final long LONG_TERM_CACHE_TTL_MS = 300000; // 5 minutes
    private static final long SLOW_OPERATION_THRESHOLD = 1000; // 1 second

    private static class CacheEntry {
        final Object value;
        final long timestamp;
        final long accessCount;
        final String category;

        CacheEntry(Object value, String category) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
            this.accessCount = 1;
            this.category = category;
        }

        boolean isExpired(long ttl) {
            return (System.currentTimeMillis() - timestamp) > ttl;
        }
    }

    private PerformanceOptimizer() {
        this.config = ProductionConfig.getInstance();

        // Create optimized thread pool
        this.commandExecutor = new ThreadPoolExecutor(
            config.getThreadPoolCoreSize(),
            config.getThreadPoolMaxSize(),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "sleuth-perf-" + System.currentTimeMillis());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // Maintenance executor for cache cleanup and optimization
        this.maintenanceExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sleuth-maintenance");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        // Schedule periodic maintenance
        maintenanceExecutor.scheduleAtFixedRate(this::performMaintenance, 60, 60, TimeUnit.SECONDS);

        // Register JMX MBean
        registerMBean();
    }

    public static synchronized PerformanceOptimizer getInstance() {
        if (instance == null) {
            instance = new PerformanceOptimizer();
        }
        return instance;
    }

    public <T> CompletableFuture<T> executeAsync(Supplier<T> operation, String operationName) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            totalOperations.incrementAndGet();

            try {
                T result = operation.get();
                long duration = System.currentTimeMillis() - startTime;

                if (duration > SLOW_OPERATION_THRESHOLD) {
                    slowOperations.incrementAndGet();
                    System.out.println("Slow operation detected: " + operationName + " took " + duration + "ms");
                }

                return result;
            } catch (Exception e) {
                throw new RuntimeException("Operation failed: " + operationName, e);
            }
        }, commandExecutor);
    }

    @SuppressWarnings("unchecked")
    public <T> T getCachedResult(String key, Supplier<T> supplier) {
        return getCachedResult(key, supplier, "default", DEFAULT_CACHE_TTL_MS);
    }

    @SuppressWarnings("unchecked")
    public <T> T getCachedResult(String key, Supplier<T> supplier, String category, long ttl) {
        // Check short-term cache first
        CacheEntry entry = resultCache.get(key);
        if (entry != null && !entry.isExpired(ttl)) {
            cacheHits.incrementAndGet();
            return (T) entry.value;
        }

        // Check long-term cache for static data
        if (isStaticCategory(category)) {
            entry = longTermCache.get(key);
            if (entry != null && !entry.isExpired(LONG_TERM_CACHE_TTL_MS)) {
                cacheHits.incrementAndGet();
                // Promote to short-term cache
                resultCache.put(key, new CacheEntry(entry.value, category));
                return (T) entry.value;
            }
        }

        // Cache miss - compute result
        cacheMisses.incrementAndGet();
        T result = supplier.get();

        CacheEntry newEntry = new CacheEntry(result, category);
        resultCache.put(key, newEntry);

        // Store in long-term cache if it's static data
        if (isStaticCategory(category)) {
            longTermCache.put(key, newEntry);
        }

        return result;
    }

    private boolean isStaticCategory(String category) {
        return "class".equals(category) || "method".equals(category) ||
               "decompile".equals(category) || "classloader".equals(category);
    }

    public void clearCache() {
        resultCache.clear();
        longTermCache.clear();
        System.out.println("Performance cache cleared");
    }

    public void clearExpiredCache() {
        long currentTime = System.currentTimeMillis();
        int expiredCount = 0;

        // Clean short-term cache
        expiredCount += resultCache.entrySet().removeIf(entry -> {
            return entry.getValue().isExpired(DEFAULT_CACHE_TTL_MS);
        });

        // Clean long-term cache
        expiredCount += longTermCache.entrySet().removeIf(entry -> {
            return entry.getValue().isExpired(LONG_TERM_CACHE_TTL_MS);
        });

        if (expiredCount > 0) {
            System.out.println("Cleaned " + expiredCount + " expired cache entries");
        }
    }

    private void performMaintenance() {
        try {
            clearExpiredCache();
            optimizeThreadPool();
            System.gc(); // Suggest garbage collection during maintenance
        } catch (Exception e) {
            System.err.println("Error during performance maintenance: " + e.getMessage());
        }
    }

    private void optimizeThreadPool() {
        if (commandExecutor instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) commandExecutor;

            // Adjust pool size based on current load
            int activeCount = tpe.getActiveCount();
            int poolSize = tpe.getPoolSize();

            if (activeCount > poolSize * 0.8 && poolSize < config.getThreadPoolMaxSize()) {
                // Increase core pool size if heavily loaded
                tpe.setCorePoolSize(Math.min(poolSize + 1, config.getThreadPoolMaxSize()));
            } else if (activeCount < poolSize * 0.2 && poolSize > config.getThreadPoolCoreSize()) {
                // Decrease core pool size if lightly loaded
                tpe.setCorePoolSize(Math.max(poolSize - 1, config.getThreadPoolCoreSize()));
            }
        }
    }

    public void shutdown() {
        System.out.println("Shutting down performance optimizer...");

        // Shutdown maintenance first
        maintenanceExecutor.shutdown();

        // Shutdown command executor gracefully
        commandExecutor.shutdown();
        try {
            if (!commandExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.out.println("Command executor did not terminate gracefully, forcing shutdown...");
                commandExecutor.shutdownNow();
                if (!commandExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Command executor did not terminate after force shutdown");
                }
            }
        } catch (InterruptedException e) {
            commandExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Final maintenance
        try {
            if (!maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                maintenanceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            maintenanceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        clearCache();
        unregisterMBean();

        System.out.println("Performance optimizer shutdown complete");
    }

    // JMX Management
    private void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.javasleuth:type=PerformanceOptimizer");
            if (!server.isRegistered(name)) {
                server.registerMBean(this, name);
                System.out.println("PerformanceOptimizer MBean registered");
            }
        } catch (Exception e) {
            System.err.println("Failed to register PerformanceOptimizer MBean: " + e.getMessage());
        }
    }

    private void unregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.javasleuth:type=PerformanceOptimizer");
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
                System.out.println("PerformanceOptimizer MBean unregistered");
            }
        } catch (Exception e) {
            System.err.println("Failed to unregister PerformanceOptimizer MBean: " + e.getMessage());
        }
    }

    // JMX Interface Implementation
    @Override
    public long getCacheHitRatio() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        return total > 0 ? (hits * 100) / total : 0;
    }

    @Override
    public long getTotalOperations() {
        return totalOperations.get();
    }

    @Override
    public long getSlowOperations() {
        return slowOperations.get();
    }

    @Override
    public int getShortTermCacheSize() {
        return resultCache.size();
    }

    @Override
    public int getLongTermCacheSize() {
        return longTermCache.size();
    }

    @Override
    public int getActiveThreads() {
        if (commandExecutor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) commandExecutor).getActiveCount();
        }
        return 0;
    }

    @Override
    public int getPoolSize() {
        if (commandExecutor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) commandExecutor).getPoolSize();
        }
        return 0;
    }

    @Override
    public void clearAllCaches() {
        clearCache();
    }

    @Override
    public void forceGarbageCollection() {
        System.gc();
    }

    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("cacheHitRatio", getCacheHitRatio());
        metrics.put("totalOperations", getTotalOperations());
        metrics.put("slowOperations", getSlowOperations());
        metrics.put("shortTermCacheSize", getShortTermCacheSize());
        metrics.put("longTermCacheSize", getLongTermCacheSize());
        metrics.put("activeThreads", getActiveThreads());
        metrics.put("poolSize", getPoolSize());
        return metrics;
    }

    public static String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        } else if (durationMs < 60000) {
            return String.format("%.2fs", durationMs / 1000.0);
        } else {
            long minutes = durationMs / 60000;
            long seconds = (durationMs % 60000) / 1000;
            return minutes + "m " + seconds + "s";
        }
    }

    // Connection pooling utilities for future database connections
    public static class ConnectionPool {
        private final BlockingQueue<Object> availableConnections;
        private final int maxSize;

        public ConnectionPool(int maxSize) {
            this.maxSize = maxSize;
            this.availableConnections = new LinkedBlockingQueue<>(maxSize);
        }

        public boolean returnConnection(Object connection) {
            return availableConnections.offer(connection);
        }

        public Object borrowConnection(long timeoutMs) throws InterruptedException {
            return availableConnections.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        public int getAvailableConnections() {
            return availableConnections.size();
        }

        public int getMaxSize() {
            return maxSize;
        }
    }

    // Static convenience methods for backward compatibility
    public static <T> CompletableFuture<T> executeAsync(Supplier<T> operation, String operationName) {
        return getInstance().executeAsync(operation, operationName);
    }

    public static <T> T getCachedResult(String key, Supplier<T> supplier) {
        return getInstance().getCachedResult(key, supplier);
    }

    public static void clearCache() {
        getInstance().clearCache();
    }

    public static void clearExpiredCache() {
        getInstance().clearExpiredCache();
    }

    public static void shutdown() {
        if (instance != null) {
            instance.shutdown();
        }
    }
}