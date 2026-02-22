package com.javasleuth.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bootstrap attach 入口闩锁（支持 detach → re-attach）。
 *
 * <p>该类用于集中管理 attach 的一次性进入控制与 reset 语义：
 * <ul>
 *   <li>启动阶段：只允许一次进入（CAS）</li>
 *   <li>启动失败：允许回滚闩锁以便重试</li>
 *   <li>shutdown/detach：由 core best-effort 触发 reset，打通 re-attach</li>
 * </ul>
 *
 * <p>注意：reset 方法是 internal hook（供 core 反射调用），不应作为对外稳定 API 承诺。</p>
 */
public final class BootstrapAttachGate {
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);

    private BootstrapAttachGate() {}

    public static boolean tryEnter() {
        return ATTACHED.compareAndSet(false, true);
    }

    /**
     * 仅用于 bootstrap 启动失败回滚（core 尚未成功启动）。
     */
    public static void releaseOnFailure() {
        ATTACHED.set(false);
    }

    /**
     * detach/shutdown 后重置闩锁，允许同 JVM 重新 attach。
     *
     * <p>该方法可能在 core 的隔离 ClassLoader 中通过反射调用，因此保持签名稳定且不依赖任何三方库。</p>
     */
    @SuppressWarnings("unused")
    public static void resetForReattach() {
        ATTACHED.set(false);
    }

    @SuppressWarnings("unused")
    public static boolean isAttached() {
        return ATTACHED.get();
    }
}

