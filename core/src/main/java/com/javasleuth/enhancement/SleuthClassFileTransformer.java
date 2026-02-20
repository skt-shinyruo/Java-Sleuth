package com.javasleuth.enhancement;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.util.SleuthLogger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SleuthClassFileTransformer implements ClassFileTransformer {
    /**
     * 以 (className + loaderId) 为维度管理 enhancer，避免多 ClassLoader 同名类互相污染。
     */
    private static final class EnhancerKey {
        private final String className;
        private final int loaderId;

        private EnhancerKey(String className, int loaderId) {
            this.className = className;
            this.loaderId = loaderId;
        }

        static EnhancerKey of(String className, ClassLoader loader) {
            return new EnhancerKey(className, loaderId(loader));
        }

        static EnhancerKey of(Class<?> clazz) {
            if (clazz == null) {
                return new EnhancerKey("<null>", 0);
            }
            return new EnhancerKey(clazz.getName(), loaderId(clazz.getClassLoader()));
        }

        static int loaderId(ClassLoader loader) {
            return loader == null ? 0 : System.identityHashCode(loader);
        }

        String formatLoaderId() {
            if (loaderId == 0) {
                return "bootstrap(0)";
            }
            return "0x" + Integer.toHexString(loaderId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            EnhancerKey that = (EnhancerKey) o;
            if (loaderId != that.loaderId) {
                return false;
            }
            if (className == null) {
                return that.className == null;
            }
            return className.equals(that.className);
        }

        @Override
        public int hashCode() {
            int result = className != null ? className.hashCode() : 0;
            result = 31 * result + loaderId;
            return result;
        }
    }

    private final ConcurrentHashMap<EnhancerKey, CopyOnWriteArrayList<ClassEnhancer>> enhancers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EnhancerKey, FailureState> failures = new ConcurrentHashMap<>();
    private final ProductionConfig config;
    private final AtomicLong transformationCount = new AtomicLong(0);
    private final AtomicLong enhancementFailureCount = new AtomicLong(0);
    private final AtomicLong enhancementSuppressedCount = new AtomicLong(0);

    public SleuthClassFileTransformer(ProductionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    private static final class FailureState {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long nextRetryAtMs = 0L;
        private volatile long lastFailureAtMs = 0L;
        private volatile long lastLogAtMs = 0L;
        private volatile String lastError = null;

        private boolean shouldAttempt(long now) {
            return now >= nextRetryAtMs;
        }

        private boolean recordFailure(long now, Throwable error, long baseCooldownMs, long logIntervalMs) {
            int n = count.incrementAndGet();
            lastFailureAtMs = now;
            lastError = error == null ? null : (error.getClass().getName() + ": " + error.getMessage());

            long base = baseCooldownMs > 0 ? baseCooldownMs : 30_000L;
            int exp = Math.min(Math.max(0, n - 1), 4); // 1,2,4,8,16
            long factor = 1L << exp;
            long delay;
            try {
                delay = Math.multiplyExact(base, factor);
            } catch (ArithmeticException overflow) {
                delay = base;
            }
            nextRetryAtMs = now + delay;

            boolean shouldLog = n == 1 || logIntervalMs <= 0 || now - lastLogAtMs >= logIntervalMs;
            if (shouldLog) {
                lastLogAtMs = now;
            }
            return shouldLog;
        }
    }

    public void addEnhancer(Class<?> targetClass, ClassEnhancer enhancer) {
        EnhancerKey key = EnhancerKey.of(targetClass);
        enhancers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(enhancer);
    }

    public void addEnhancer(String className, ClassLoader loader, ClassEnhancer enhancer) {
        EnhancerKey key = EnhancerKey.of(className, loader);
        enhancers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(enhancer);
    }

    public void removeEnhancer(String className) {
        if (className == null) {
            return;
        }
        for (EnhancerKey k : enhancers.keySet()) {
            if (className.equals(k.className)) {
                enhancers.remove(k);
                failures.remove(k);
            }
        }
    }

    public void removeEnhancer(Class<?> targetClass, ClassEnhancer enhancer) {
        EnhancerKey key = EnhancerKey.of(targetClass);
        removeEnhancer(key, enhancer);
    }

    public void removeEnhancer(String className, ClassLoader loader, ClassEnhancer enhancer) {
        EnhancerKey key = EnhancerKey.of(className, loader);
        removeEnhancer(key, enhancer);
    }

    private void removeEnhancer(EnhancerKey key, ClassEnhancer enhancer) {
        if (key == null) {
            return;
        }
        CopyOnWriteArrayList<ClassEnhancer> list = enhancers.get(key);
        if (list == null) {
            return;
        }
        list.remove(enhancer);
        if (list.isEmpty()) {
            enhancers.remove(key);
            failures.remove(key);
        }
    }

    public void removeAllEnhancers() {
        enhancers.clear();
        failures.clear();
    }

    public Set<String> getEnhancedClassNames() {
        Set<String> out = new HashSet<>();
        for (EnhancerKey k : enhancers.keySet()) {
            if (k != null && k.className != null) {
                out.add(k.className);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                          ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {

        if (className == null || shouldSkipClass(className)) {
            return null;
        }

        String normalizedClassName = className.replace('/', '.');
        ClassLoader effectiveLoader = classBeingRedefined != null ? classBeingRedefined.getClassLoader() : loader;
        EnhancerKey key = EnhancerKey.of(normalizedClassName, effectiveLoader);
        CopyOnWriteArrayList<ClassEnhancer> list = enhancers.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }

        long now = System.currentTimeMillis();
        FailureState failureState = failures.get(key);
        if (failureState != null && !failureState.shouldAttempt(now)) {
            enhancementSuppressedCount.incrementAndGet();
            return null;
        }

        try {
            transformationCount.incrementAndGet();

            ClassReader classReader = new ClassReader(classfileBuffer);
            ClassWriter classWriter = new LoaderAwareClassWriter(
                classReader,
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES,
                effectiveLoader
            );

            List<ClassEnhancer> snapshot = new ArrayList<>(list);
            EnhancerChain chain = new EnhancerChain(snapshot);
            ClassVisitor enhancedVisitor = chain.createClassVisitor(classWriter, normalizedClassName);
            classReader.accept(enhancedVisitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = classWriter.toByteArray();

            SleuthLogger.debug("Enhanced class " + normalizedClassName +
                " (transformation #" + transformationCount.get() + ")");

            // Clear failure state after a successful transformation.
            if (failureState != null) {
                failures.remove(key);
            }
            return transformedBytes;

        } catch (Exception e) {
            enhancementFailureCount.incrementAndGet();
            FailureState st = failures.computeIfAbsent(key, k -> new FailureState());
            long cooldownMs = config.getLong("enhancement.failure.cooldown.ms", 30_000L);
            long logIntervalMs = config.getLong("enhancement.failure.log.interval.ms", 60_000L);
            boolean shouldLog = st.recordFailure(now, e, cooldownMs, logIntervalMs);

            // 不再移除 enhancer，避免 watch/trace 等“静默失效”。失败会进入冷却并可重试。
            if (shouldLog) {
                SleuthLogger.error("Failed to enhance class " + normalizedClassName +
                    " (loaderId=" + key.formatLoaderId() + "): " + e.getMessage(), e);
            }
            return null;
        }
    }

    private boolean shouldSkipClass(String className) {
        return className.startsWith("java/") ||
               className.startsWith("javax/") ||
               className.startsWith("sun/") ||
               className.startsWith("com/sun/") ||
               className.startsWith("org/objectweb/asm/") ||
               className.startsWith("com/javasleuth/") ||
               // Allow common proxy classes (e.g. Spring/CGLIB) but keep filtering noisy synthetic classes.
               className.contains("$$Lambda$");
    }

    public long getTransformationCount() {
        return transformationCount.get();
    }

    public long getEnhancementFailureCount() {
        return enhancementFailureCount.get();
    }

    public long getEnhancementSuppressedCount() {
        return enhancementSuppressedCount.get();
    }

    public int getEnhancementCooldownCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (FailureState s : failures.values()) {
            if (s != null && now < s.nextRetryAtMs) {
                n++;
            }
        }
        return n;
    }

    public int getActiveEnhancersCount() {
        int count = 0;
        for (CopyOnWriteArrayList<ClassEnhancer> list : enhancers.values()) {
            count += list.size();
        }
        return count;
    }
}
