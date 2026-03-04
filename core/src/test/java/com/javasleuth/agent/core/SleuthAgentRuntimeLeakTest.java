package com.javasleuth.core.agent.core;

import com.javasleuth.core.agent.runtime.SleuthAgentRuntime;
import com.javasleuth.test.SleuthTestState;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Regression guard for detach → re-attach resource leaks.
 *
 * <p>Ensures we can create/close a runtime multiple times in the same JVM without accumulating:
 * <ul>
 *   <li>Threads (name prefix: {@code sleuth-})</li>
 *   <li>JMX MBeans under {@code com.javasleuth:*} (esp. PerformanceOptimizer/MemoryOptimizer/MetricsCollector)</li>
 * </ul>
 */
public class SleuthAgentRuntimeLeakTest {

    private static final long SETTLE_TIMEOUT_MS = 2000;
    private static final long POLL_INTERVAL_MS = 50;
    private static final int CYCLES = 5;

    @After
    public void tearDown() {
        SleuthTestState.resetAll("after_test");
    }

    @Test
    public void createClose_doesNotLeakThreadsOrMBeans_acrossCycles() throws Exception {
        SleuthTestState.resetAll("before_test");

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName perfName = new ObjectName("com.javasleuth:type=PerformanceOptimizer");
        ObjectName memName = new ObjectName("com.javasleuth:type=MemoryOptimizer");
        ObjectName metricsName = new ObjectName("com.javasleuth:type=MetricsCollector");

        Set<String> baselineThreads =
            awaitStableSnapshot(SleuthAgentRuntimeLeakTest::snapshotSleuthThreadNames, SETTLE_TIMEOUT_MS);
        Set<ObjectName> baselineMBeans =
            awaitStableSnapshot(() -> snapshotSleuthMBeans(server), SETTLE_TIMEOUT_MS);

        boolean baselinePerf = server.isRegistered(perfName);
        boolean baselineMem = server.isRegistered(memName);
        boolean baselineMetrics = server.isRegistered(metricsName);

        for (int i = 0; i < CYCLES; i++) {
            SleuthAgentRuntime runtime = SleuthAgentRuntime.create(fakeInstrumentation(), () -> {});
            runtime.close();

            assertEventuallyLeakFree(
                i,
                baselineThreads,
                baselineMBeans,
                server,
                perfName,
                baselinePerf,
                memName,
                baselineMem,
                metricsName,
                baselineMetrics
            );
        }
    }

    private static void assertEventuallyLeakFree(
        int cycleIndex,
        Set<String> baselineThreads,
        Set<ObjectName> baselineMBeans,
        MBeanServer server,
        ObjectName perfName,
        boolean baselinePerf,
        ObjectName memName,
        boolean baselineMem,
        ObjectName metricsName,
        boolean baselineMetrics
    ) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_TIMEOUT_MS);

        Set<String> threads = Collections.emptySet();
        Set<ObjectName> mbeans = Collections.emptySet();
        boolean perf = false;
        boolean mem = false;
        boolean metrics = false;

        while (System.nanoTime() < deadlineNanos) {
            threads = snapshotSleuthThreadNames();
            mbeans = snapshotSleuthMBeans(server);
            perf = server.isRegistered(perfName);
            mem = server.isRegistered(memName);
            metrics = server.isRegistered(metricsName);

            if (baselineThreads.equals(threads)
                && baselineMBeans.equals(mbeans)
                && perf == baselinePerf
                && mem == baselineMem
                && metrics == baselineMetrics) {
                return;
            }

            Thread.sleep(POLL_INTERVAL_MS);
        }

        TreeSet<String> extraThreads = new TreeSet<>(threads);
        extraThreads.removeAll(baselineThreads);
        TreeSet<String> missingThreads = new TreeSet<>(baselineThreads);
        missingThreads.removeAll(threads);

        TreeSet<String> extraMBeans = new TreeSet<>(toCanonicalStrings(mbeans));
        extraMBeans.removeAll(toCanonicalStrings(baselineMBeans));
        TreeSet<String> missingMBeans = new TreeSet<>(toCanonicalStrings(baselineMBeans));
        missingMBeans.removeAll(toCanonicalStrings(mbeans));

        String message =
            "Detected detach→re-attach leak after cycle " + (cycleIndex + 1) + "/" + CYCLES + " (waited ~"
                + SETTLE_TIMEOUT_MS + "ms)\n"
                + "Threads baseline=" + baselineThreads.size() + ", current=" + threads.size() + "\n"
                + "Extra threads=" + trimForLog(extraThreads) + "\n"
                + "Missing threads=" + trimForLog(missingThreads) + "\n"
                + "MBeans baseline=" + baselineMBeans.size() + ", current=" + mbeans.size() + "\n"
                + "Extra MBeans=" + trimForLog(extraMBeans) + "\n"
                + "Missing MBeans=" + trimForLog(missingMBeans) + "\n"
                + perfName + " registered baseline=" + baselinePerf + ", current=" + perf + "\n"
                + memName + " registered baseline=" + baselineMem + ", current=" + mem + "\n"
                + metricsName + " registered baseline=" + baselineMetrics + ", current=" + metrics + "\n";

        Assert.fail(message);
    }

    private static Set<String> snapshotSleuthThreadNames() {
        Set<String> names = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t == null) {
                continue;
            }
            String name = t.getName();
            if (name == null) {
                continue;
            }
            if (name.startsWith("sleuth-")) {
                names.add(name);
            }
        }
        return names;
    }

    private static Set<ObjectName> snapshotSleuthMBeans(MBeanServer server) {
        try {
            return new HashSet<>(server.queryNames(new ObjectName("com.javasleuth:*"), null));
        } catch (Exception e) {
            // If JMX is unavailable for some reason, fail loudly (this test is specifically about JMX cleanup).
            throw new AssertionError("Failed to query com.javasleuth MBeans: " + e.getMessage(), e);
        }
    }

    private static <T> T awaitStableSnapshot(Supplier<T> supplier, long timeoutMs) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        T prev = supplier.get();
        while (System.nanoTime() < deadlineNanos) {
            Thread.sleep(POLL_INTERVAL_MS);
            T cur = supplier.get();
            if (cur == null ? prev == null : cur.equals(prev)) {
                return cur;
            }
            prev = cur;
        }
        return prev;
    }

    private static List<String> toCanonicalStrings(Set<ObjectName> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(names.size());
        for (ObjectName n : names) {
            out.add(n == null ? "null" : n.getCanonicalName());
        }
        Collections.sort(out);
        return out;
    }

    private static String trimForLog(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        int limit = 25;
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int i = 0;
        for (String v : values) {
            if (i > 0) {
                sb.append(", ");
            }
            if (i >= limit) {
                sb.append("... (").append(values.size() - limit).append(" more)");
                break;
            }
            sb.append(v);
            i++;
        }
        sb.append(']');
        return sb.toString();
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return new Class<?>[0];
                }
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("removeTransformer".equals(name)) {
                    return true;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return false;
                }
                if (returnType == Integer.TYPE) {
                    return 0;
                }
                if (returnType == Long.TYPE) {
                    return 0L;
                }
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                return null;
            }
        );
    }
}

