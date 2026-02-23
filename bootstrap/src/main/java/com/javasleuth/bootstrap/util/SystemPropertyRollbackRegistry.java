package com.javasleuth.bootstrap.util;

import java.util.concurrent.atomic.AtomicReference;

/**
 * attach 期间 sysprop 回滚句柄的 bootstrap 可见注册表（SSOT）。
 *
 * <p>目标：跨 classloader 边界共享同一个回滚句柄，并在 detach/shutdown 时统一回滚与清理。</p>
 */
public final class SystemPropertyRollbackRegistry {
    private static final AtomicReference<SystemPropertyRollback> CURRENT = new AtomicReference<>();

    private SystemPropertyRollbackRegistry() {}

    public static boolean isRegistered() {
        return CURRENT.get() != null;
    }

    /**
     * 若当前 attach 未注册回滚句柄，则应用 agentArgs 并注册；否则不重复应用，直接返回已注册句柄。
     *
     * <p>该方法用于统一入口（agent/container/core）调用，避免重复 apply 导致回滚基线漂移。</p>
     */
    public static SystemPropertyRollback applyAndRegisterIfAbsent(String agentArgs) {
        SystemPropertyRollback existing = CURRENT.get();
        if (existing != null) {
            return existing;
        }

        SystemPropertyRollback rollback = AgentArgsApplier.applyToSystemPropertiesWithRollback(agentArgs);
        if (rollback == null) {
            rollback = SystemPropertyRollback.noop();
        }

        if (CURRENT.compareAndSet(null, rollback)) {
            return rollback;
        }

        // 竞争失败：以已注册句柄为准。此处不回滚本次 apply，避免误回滚另一路径已生效配置。
        // 该竞争在正常启动链路中不应发生（同一线程串行），保留 best-effort 容错。
        SystemPropertyRollback after = CURRENT.get();
        return after != null ? after : rollback;
    }

    public static void rollbackAndClearBestEffort() {
        SystemPropertyRollback rollback = CURRENT.getAndSet(null);
        if (rollback == null) {
            return;
        }
        try {
            rollback.rollbackBestEffort();
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    public static void clearWithoutRollback() {
        CURRENT.set(null);
    }
}

