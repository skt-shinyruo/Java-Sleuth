package com.javasleuth.launcher.attach;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import java.io.File;

/**
 * AgentArgs 构建器。
 *
 * <p>负责从 ProductionConfig 与 CLI 选项拼装最终传给 agent 的参数字符串。</p>
 */
public final class AgentArgsBuilder {
    public static final class BuildResult {
        private final boolean ok;
        private final String agentArgs;
        private final String errorMessage;

        private BuildResult(boolean ok, String agentArgs, String errorMessage) {
            this.ok = ok;
            this.agentArgs = agentArgs;
            this.errorMessage = errorMessage;
        }

        public static BuildResult ok(String agentArgs) {
            return new BuildResult(true, agentArgs, null);
        }

        public static BuildResult error(String message) {
            return new BuildResult(false, null, message);
        }

        public boolean isOk() {
            return ok;
        }

        public String getAgentArgs() {
            return agentArgs;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    public BuildResult build(ProductionConfig config, File containerJar) {
        if (config == null) {
            return BuildResult.error("Internal error: ProductionConfig is null");
        }

        String configFile = resolveConfigFile();

        StringBuilder agentArgs = new StringBuilder();
        if (containerJar != null) {
            agentArgs.append("containerJar=").append(containerJar.getAbsolutePath()).append(";");
        }
        if (configFile != null && !configFile.trim().isEmpty()) {
            agentArgs.append("configFile=").append(configFile).append(";");
        }
        agentArgs.append("server.port=").append(SleuthConfigSchema.SERVER_PORT.read(config)).append(";");
        agentArgs.append("protocol.streaming.enabled=").append(SleuthConfigSchema.PROTOCOL_STREAMING_ENABLED.read(config)).append(";");

        return BuildResult.ok(agentArgs.toString());
    }

    private static String resolveConfigFile() {
        String configFile = System.getProperty("sleuth.config.file");
        if (configFile == null || configFile.trim().isEmpty()) {
            File local = new File("sleuth.properties");
            if (local.exists()) {
                configFile = local.getAbsolutePath();
            }
        }
        return configFile;
    }

}
