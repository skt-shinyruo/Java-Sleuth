package com.javasleuth.core.agent.core;

import java.lang.instrument.Instrumentation;

/**
 * Bootstrap 入口闩锁重置器（best-effort）。
 *
 * <p>core 运行在隔离 ClassLoader（URLClassLoader parent=null）中，不能以编译期依赖直接引用 agent 模块类；
 * 因此这里通过反射调用 {@code com.javasleuth.agent.BootstrapAttachGate#resetForReattach()}，以支持
 * detach → re-attach 生命周期闭环。</p>
 *
 * <p>注意：该逻辑必须是 best-effort，不能影响 shutdown 主流程。</p>
 */
public final class BootstrapAttachGateReset {
    private static final String GATE_CLASS = "com.javasleuth.agent.BootstrapAttachGate";
    private static final String RESET_METHOD = "resetForReattach";

    private BootstrapAttachGateReset() {}

    public static void resetBestEffort(Instrumentation instrumentation) {
        // 优先尝试 system classloader（最符合 agent 入口类的加载方式）。
        if (tryResetWithClassLoader(ClassLoader.getSystemClassLoader())) {
            return;
        }

        // 其次尝试当前线程的 TCCL（某些 attach 环境会调整 TCCL）。
        ClassLoader tccl = null;
        try {
            tccl = Thread.currentThread().getContextClassLoader();
        } catch (Exception ignore) {
            tccl = null;
        }
        if (tryResetWithClassLoader(tccl)) {
            return;
        }

        // 最后兜底：扫描已加载类（可能存在多份同名类，不同 classloader；尽量都 reset）。
        if (instrumentation != null) {
            resetWithInstrumentationBestEffort(instrumentation);
        }
    }

    private static boolean tryResetWithClassLoader(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            Class<?> gate = Class.forName(GATE_CLASS, false, loader);
            java.lang.reflect.Method m = gate.getMethod(RESET_METHOD);
            m.invoke(null);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void resetWithInstrumentationBestEffort(Instrumentation instrumentation) {
        try {
            Class<?>[] loaded = instrumentation.getAllLoadedClasses();
            if (loaded == null || loaded.length == 0) {
                return;
            }
            for (Class<?> c : loaded) {
                if (c == null) {
                    continue;
                }
                if (!GATE_CLASS.equals(c.getName())) {
                    continue;
                }
                try {
                    java.lang.reflect.Method m = c.getMethod(RESET_METHOD);
                    m.invoke(null);
                } catch (Throwable ignore) {
                    // ignore per-class
                }
            }
        } catch (Throwable ignore) {
            // best-effort
        }
    }
}

