package com.javasleuth.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试替身：用于在 core 模块的单元测试中验证
 * {@code com.javasleuth.core.agent.core.BootstrapAttachGateReset} 的反射 reset 逻辑。
 *
 * <p>注意：core 模块不依赖 agent 模块，因此这里通过 test sources 提供同名类，
 * 以便在 surefire 的测试类加载器中可被反射定位与调用。</p>
 */
public final class BootstrapAttachGate {
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);

    private BootstrapAttachGate() {}

    public static boolean tryEnter() {
        return ATTACHED.compareAndSet(false, true);
    }

    public static void releaseOnFailure() {
        ATTACHED.set(false);
    }

    public static void resetForReattach() {
        ATTACHED.set(false);
    }

    public static boolean isAttached() {
        return ATTACHED.get();
    }
}

