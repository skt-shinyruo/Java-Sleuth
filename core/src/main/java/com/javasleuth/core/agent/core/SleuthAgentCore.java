package com.javasleuth.core.agent.core;

/**
 * Agent 核心入口（attach/premain），作为核心组合根（composition root）。
 *
 * <p>该类负责组装并启动命令服务与 transformer，尽量避免将单例获取与全局副作用散落到各业务对象构造阶段。</p>
 */
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.agent.runtime.SleuthAgentRuntime;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public class SleuthAgentCore {
    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);
    private static final AtomicReference<SleuthAgentRuntime> RUNTIME = new AtomicReference<>();

    public static void agentmain(String agentArgs, Instrumentation inst) {
        SleuthAgentEntrypointSupport.agentmain(
            agentArgs,
            inst,
            ATTACHED,
            RUNTIME,
            SleuthAgentCore::shutdown,
            "Java-Sleuth agent is already attached to this JVM",
            "Java-Sleuth agent attached successfully",
            "Failed to start Java-Sleuth agent: "
        );
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        SleuthAgentEntrypointSupport.premain(
            agentArgs,
            inst,
            ATTACHED,
            RUNTIME,
            SleuthAgentCore::shutdown,
            "Java-Sleuth agent is already attached to this JVM",
            "Java-Sleuth agent attached successfully",
            "Failed to start Java-Sleuth agent: "
        );
    }

    public static SleuthClassFileTransformer getTransformer() {
        SleuthAgentRuntime runtime = RUNTIME.get();
        return runtime != null ? runtime.getTransformer() : null;
    }

    public static Instrumentation getInstrumentation() {
        SleuthAgentRuntime runtime = RUNTIME.get();
        return runtime != null ? runtime.getInstrumentation() : null;
    }

    public static boolean isAttached() {
        return ATTACHED.get();
    }

    /**
     * Best-effort safe shutdown.
     *
     * <p>Goal: behave like a full reset rollback before removing the transformer.
     * This minimizes residual instrumentation state if shutdown happens mid-command.
     */
    public static void shutdown() {
        SleuthAgentEntrypointSupport.shutdown(
            ATTACHED,
            RUNTIME,
            SleuthAgentCore.class.getClassLoader(),
            "Java-Sleuth agent shutdown",
            null
        );
    }
}
