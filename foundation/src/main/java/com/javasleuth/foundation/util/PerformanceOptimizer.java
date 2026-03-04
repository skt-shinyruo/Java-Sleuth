package com.javasleuth.foundation.util;

import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import java.lang.management.ManagementFactory;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.*;

public class PerformanceOptimizer implements PerformanceOptimizerMBean {
    private static PerformanceOptimizer instance;
    private final ConfigView config;

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

    private PerformanceOptimizer(ConfigView config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;

        // Create optimized thread pool
        int coreSize = SleuthConfigSchema.PERFORMANCE_THREAD_POOL_CORE.read(config);
        int maxSize = SleuthConfigSchema.PERFORMANCE_THREAD_POOL_MAX.read(config);
        this.commandExecutor = new ThreadPoolExecutor(
            coreSize,
            maxSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            SleuthThreadFactory.daemon("sleuth-perf"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // Maintenance executor for cache cleanup and optimization
        this.maintenanceExecutor = Executors.newScheduledThreadPool(
            2,
            SleuthThreadFactory.daemon("sleuth-maintenance", Thread.MIN_PRIORITY)
        );

        // Schedule periodic maintenance
        maintenanceExecutor.scheduleAtFixedRate(this::performMaintenance, 60, 60, TimeUnit.SECONDS);

        // Register JMX MBean
        registerMBean();
    }

    private boolean isPerformanceLogEnabled() {
        return SleuthConfigSchema.LOGGING_PERFORMANCE_ENABLED.read(config);
    }

    public static synchronized PerformanceOptimizer getInstance(ConfigView config) {
        if (instance == null) {
            instance = new PerformanceOptimizer(config);
        }
        return instance;
    }

    public static synchronized PerformanceOptimizer getInstance() {
        return getInstance(ProductionConfig.createDefault());
    }

    private <T> CompletableFuture<T> executeAsyncInternal(Supplier<T> operation, String operationName) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            totalOperations.incrementAndGet();

            try {
                T result = operation.get();
                long duration = System.currentTimeMillis() - startTime;

                if (duration > SLOW_OPERATION_THRESHOLD) {
                    slowOperations.incrementAndGet();
                    if (isPerformanceLogEnabled()) {
                        SleuthLogger.warn("Slow operation detected: " + operationName + " took " + duration + "ms");
                    }
                }

                return result;
            } catch (Exception e) {
                throw new RuntimeException("Operation failed: " + operationName, e);
            }
        }, commandExecutor);
    }

    @SuppressWarnings("unchecked")
    private <T> T getCachedResultInternal(String key, Supplier<T> supplier) {
        return getCachedResultInternal(key, supplier, "default", DEFAULT_CACHE_TTL_MS);
    }

    @SuppressWarnings("unchecked")
    private <T> T getCachedResultInternal(String key, Supplier<T> supplier, String category, long ttl) {
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

    private void clearCacheInternal() {
        resultCache.clear();
        longTermCache.clear();
        if (isPerformanceLogEnabled()) {
            SleuthLogger.debug("Performance cache cleared");
        }
    }

    private void clearExpiredCacheInternal() {
        int expiredCount = 0;

        // Clean short-term cache
        int shortBefore = resultCache.size();
        resultCache.entrySet().removeIf(entry -> entry.getValue().isExpired(DEFAULT_CACHE_TTL_MS));
        expiredCount += Math.max(0, shortBefore - resultCache.size());

        // Clean long-term cache
        int longBefore = longTermCache.size();
        longTermCache.entrySet().removeIf(entry -> entry.getValue().isExpired(LONG_TERM_CACHE_TTL_MS));
        expiredCount += Math.max(0, longBefore - longTermCache.size());

        if (expiredCount > 0) {
            if (isPerformanceLogEnabled()) {
                SleuthLogger.debug("Cleaned " + expiredCount + " expired cache entries");
            }
        }
    }

    private void performMaintenance() {
        try {
            clearExpiredCacheInternal();
            optimizeThreadPool();
            if (SleuthConfigSchema.PERFORMANCE_MAINTENANCE_FORCE_GC.read(config)) {
                System.gc();
            }
        } catch (Exception e) {
            if (isPerformanceLogEnabled()) {
                SleuthLogger.warn("Error during performance maintenance: " + e.getMessage(), e);
            }
        }
    }

    private void optimizeThreadPool() {
        if (commandExecutor instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) commandExecutor;

            // Adjust pool size based on current load
            int activeCount = tpe.getActiveCount();
            int poolSize = tpe.getPoolSize();
            int maxSize = SleuthConfigSchema.PERFORMANCE_THREAD_POOL_MAX.read(config);
            int coreSize = SleuthConfigSchema.PERFORMANCE_THREAD_POOL_CORE.read(config);

            if (activeCount > poolSize * 0.8 && poolSize < maxSize) {
                // Increase core pool size if heavily loaded
                tpe.setCorePoolSize(Math.min(poolSize + 1, maxSize));
            } else if (activeCount < poolSize * 0.2 && poolSize > coreSize) {
                // Decrease core pool size if lightly loaded
                tpe.setCorePoolSize(Math.max(poolSize - 1, coreSize));
            }
        }
    }

    private void shutdownInternal() {
        if (isPerformanceLogEnabled()) {
            SleuthLogger.debug("Shutting down performance optimizer...");
        }

        // Shutdown maintenance first
        maintenanceExecutor.shutdown();

        // Shutdown command executor gracefully
        commandExecutor.shutdown();
        try {
            if (!commandExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                if (isPerformanceLogEnabled()) {
                    SleuthLogger.warn("Command executor did not terminate gracefully, forcing shutdown...");
                }
                commandExecutor.shutdownNow();
                if (!commandExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    if (isPerformanceLogEnabled()) {
                        SleuthLogger.error("Command executor did not terminate after force shutdown");
                    }
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

        clearCacheInternal();
        unregisterMBean();

        if (isPerformanceLogEnabled()) {
            SleuthLogger.debug("Performance optimizer shutdown complete");
        }
    }

    // JMX Management
    private void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.javasleuth:type=PerformanceOptimizer");
            if (!server.isRegistered(name)) {
                server.registerMBean(this, name);
                if (isPerformanceLogEnabled()) {
                    SleuthLogger.debug("PerformanceOptimizer MBean registered");
                }
            }
        } catch (Exception e) {
            if (isPerformanceLogEnabled()) {
                SleuthLogger.warn("Failed to register PerformanceOptimizer MBean: " + e.getMessage(), e);
            }
        }
    }

    private void unregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.javasleuth:type=PerformanceOptimizer");
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
                if (isPerformanceLogEnabled()) {
                    SleuthLogger.debug("PerformanceOptimizer MBean unregistered");
                }
            }
        } catch (Exception e) {
            if (isPerformanceLogEnabled()) {
                SleuthLogger.warn("Failed to unregister PerformanceOptimizer MBean: " + e.getMessage(), e);
            }
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
        clearCacheInternal();
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
        return getInstance().executeAsyncInternal(operation, operationName);
    }

    public static <T> T getCachedResult(String key, Supplier<T> supplier) {
        return getInstance().getCachedResultInternal(key, supplier);
    }

    public static void clearCache() {
        getInstance().clearCacheInternal();
    }

    public static void clearExpiredCache() {
        getInstance().clearExpiredCacheInternal();
    }

    public static synchronized void shutdown() {
        PerformanceOptimizer inst = instance;
        if (inst == null) {
            return;
        }
        try {
            inst.shutdownInternal();
        } finally {
            instance = null;
        }
    }
}
