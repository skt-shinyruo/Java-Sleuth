package com.javasleuth.launcher.attach;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.File;

/**
 * Agent 注入器（Attach + loadAgent）。
 *
 * <p>该类不负责选择 JVM，也不负责协议交互；仅封装 attach/load/detach 过程。</p>
 */
public final class AgentAttacher {
    private final AttachApi attachApi;
    private final AgentArgsBuilder agentArgsBuilder;

    public AgentAttacher(AttachApi attachApi, AgentArgsBuilder agentArgsBuilder) {
        this.attachApi = attachApi;
        this.agentArgsBuilder = agentArgsBuilder;
    }

    public boolean attach(String pid, String displayName, File agentJar, File containerJar) throws Exception {
        if (pid == null || pid.trim().isEmpty()) {
            System.err.println("Invalid target PID");
            return false;
        }
        if (agentJar == null || !agentJar.isFile()) {
            System.err.println("Agent JAR is missing: " + (agentJar != null ? agentJar.getPath() : "null"));
            return false;
        }

        System.out.println("Attaching to JVM: " + (displayName != null ? displayName : "") + " (PID: " + pid + ")");

        ProductionConfig config = ProductionConfig.createDefault();
        try {
            SleuthLogger.setConfigProvider(new SleuthLogger.ConfigProvider() {
                @Override
                public String getString(String key, String defaultValue) {
                    return config.getString(key, defaultValue);
                }

                @Override
                public boolean getBoolean(String key, boolean defaultValue) {
                    return config.getBoolean(key, defaultValue);
                }

                @Override
                public boolean isLoading() {
                    return false;
                }
            });
        } catch (Exception ignore) {
            // 忽略
        }

        AgentArgsBuilder.BuildResult built = agentArgsBuilder.build(config, containerJar);
        if (!built.isOk()) {
            System.err.println(built.getErrorMessage());
            return false;
        }

        VirtualMachineHandle vm = null;
        try {
            vm = attachApi.attach(pid);
            vm.loadAgent(agentJar.getAbsolutePath(), built.getAgentArgs());
            System.out.println("Agent attached successfully!");
            return true;
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }
}
