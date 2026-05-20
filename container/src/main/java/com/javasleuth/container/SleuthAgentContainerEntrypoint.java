package com.javasleuth.container;

import com.javasleuth.core.agent.core.SleuthAgentEntrypointSupport;
import com.javasleuth.core.agent.runtime.SleuthAgentRuntime;
import java.io.Closeable;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java-Sleuth container 入口（composition root）。
 *
 * <p>该入口运行在 bootstrap agent 创建的隔离 ClassLoader 中（URLClassLoader parent=null），负责：
 * 1) 启动每次 attach 对应的 runtime（可关闭、幂等）
 * 2) detach/shutdown 时收口资源回收与全局状态清理（包括通知 bootstrap 侧生命周期对象回滚 sysprop、关闭隔离 ClassLoader）
 */
public final class SleuthAgentContainerEntrypoint {
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);
    private static final AtomicReference<SleuthAgentRuntime> RUNTIME = new AtomicReference<>();

    private SleuthAgentContainerEntrypoint() {}

    public static int contractVersion() {
        return 1;
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        SleuthAgentEntrypointSupport.agentmain(
            agentArgs,
            inst,
            ATTACHED,
            RUNTIME,
            SleuthAgentContainerEntrypoint::shutdown,
            "Java-Sleuth agent is already attached to this JVM",
            "Java-Sleuth agent attached successfully (container)",
            "Failed to start Java-Sleuth agent (container): "
        );
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        SleuthAgentEntrypointSupport.premain(
            agentArgs,
            inst,
            ATTACHED,
            RUNTIME,
            SleuthAgentContainerEntrypoint::shutdown,
            "Java-Sleuth agent is already attached to this JVM",
            "Java-Sleuth agent attached successfully (container)",
            "Failed to start Java-Sleuth agent (container): "
        );
    }

    /**
     * Best-effort safe shutdown.
     *
     * <p>核心目标：detach→re-attach 幂等；不遗留线程/transformer/认证会话/监控注册表等全局状态。</p>
     */
    public static void shutdown() {
        SleuthAgentEntrypointSupport.shutdown(
            ATTACHED,
            RUNTIME,
            SleuthAgentContainerEntrypoint.class.getClassLoader(),
            "Java-Sleuth agent shutdown (container)",
            SleuthAgentContainerEntrypoint::closeOwnClassLoaderBestEffort
        );
    }

    private static void closeOwnClassLoaderBestEffort() {
        try {
            ClassLoader cl = SleuthAgentContainerEntrypoint.class.getClassLoader();
            if (cl instanceof Closeable) {
                ((Closeable) cl).close();
            }
        } catch (Throwable ignore) {
            // best-effort
        }
    }
}
