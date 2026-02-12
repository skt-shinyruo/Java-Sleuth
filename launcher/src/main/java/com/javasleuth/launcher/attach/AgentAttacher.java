package com.javasleuth.launcher.attach;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.util.SleuthLogger;
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

    public boolean attach(String pid, String displayName, File agentJar, File coreJar, boolean insecureMode) throws Exception {
        if (pid == null || pid.trim().isEmpty()) {
            System.err.println("Invalid target PID");
            return false;
        }
        if (agentJar == null || !agentJar.isFile()) {
            System.err.println("Agent JAR is missing: " + (agentJar != null ? agentJar.getPath() : "null"));
            return false;
        }

        System.out.println("Attaching to JVM: " + (displayName != null ? displayName : "") + " (PID: " + pid + ")");

        ProductionConfig config = ProductionConfig.getInstance();
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
                    return ProductionConfig.isLoading();
                }
            });
        } catch (Exception ignore) {
            // 忽略
        }

        AgentArgsBuilder.BuildResult built = agentArgsBuilder.build(config, insecureMode, coreJar);
        if (!built.isOk()) {
            System.err.println(built.getErrorMessage());
            return false;
        }

        VirtualMachineHandle vm = null;
        try {
            vm = attachApi.attach(pid);
            vm.loadAgent(agentJar.getAbsolutePath(), built.getAgentArgs());
            System.out.println("Agent attached successfully!");
            // 给予目标 JVM 少量时间启动监听与握手自举。
            Thread.sleep(2000);
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

