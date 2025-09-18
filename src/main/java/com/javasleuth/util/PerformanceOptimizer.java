package com.javasleuth.util;

import java.util.concurrent.*;
import java.util.function.Supplier;

public class PerformanceOptimizer {
    private static final ExecutorService COMMAND_EXECUTOR =
        new ThreadPoolExecutor(4, 16, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "sleuth-command-" + System.currentTimeMillis());
                t.setDaemon(true);
                return t;
            });

    private static final ConcurrentHashMap<String, Object> RESULT_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5000; // 5 second cache for expensive operations
    private static final ConcurrentHashMap<String, Long> CACHE_TIMESTAMPS = new ConcurrentHashMap<>();

    public static <T> CompletableFuture<T> executeAsync(Supplier<T> operation, String operationName) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                T result = operation.get();
                long duration = System.currentTimeMillis() - startTime;
                if (duration > 1000) {
                    System.out.println("Slow operation detected: " + operationName + " took " + duration + "ms");
                }
                return result;
            } catch (Exception e) {
                throw new RuntimeException("Operation failed: " + operationName, e);
            }
        }, COMMAND_EXECUTOR);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getCachedResult(String key, Supplier<T> supplier) {
        Long timestamp = CACHE_TIMESTAMPS.get(key);
        if (timestamp != null && (System.currentTimeMillis() - timestamp) < CACHE_TTL_MS) {
            Object cached = RESULT_CACHE.get(key);
            if (cached != null) {
                return (T) cached;
            }
        }

        T result = supplier.get();
        RESULT_CACHE.put(key, result);
        CACHE_TIMESTAMPS.put(key, System.currentTimeMillis());
        return result;
    }

    public static void clearCache() {
        RESULT_CACHE.clear();
        CACHE_TIMESTAMPS.clear();
    }

    public static void clearExpiredCache() {
        long currentTime = System.currentTimeMillis();
        CACHE_TIMESTAMPS.entrySet().removeIf(entry -> {
            if ((currentTime - entry.getValue()) > CACHE_TTL_MS) {
                RESULT_CACHE.remove(entry.getKey());
                return true;
            }
            return false;
        });
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

    public static void shutdown() {
        COMMAND_EXECUTOR.shutdown();
        try {
            if (!COMMAND_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                COMMAND_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            COMMAND_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
        clearCache();
    }
}