package com.javasleuth.launcher.attach;

import com.javasleuth.config.ConfigUpdateSource;
import com.javasleuth.config.ProductionConfig;
import java.io.File;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AgentArgs 构建器。
 *
 * <p>负责从 ProductionConfig 与 CLI 选项拼装最终传给 agent 的参数字符串。</p>
 */
public final class AgentArgsBuilder {
    private static final SecureRandom RANDOM = new SecureRandom();

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

    public BuildResult build(ProductionConfig config, boolean insecureMode, File coreJar) {
        if (config == null) {
            return BuildResult.error("Internal error: ProductionConfig is null");
        }

        String configFile = resolveConfigFile();

        String securityMode = config.getSecurityMode();
        String hmacSecret = config.getSecurityHmacSecret();
        String hmacSessionRole = config.getHmacSessionRole();

        if (insecureMode) {
            securityMode = "off";
            config.setRuntimeConfig("security.mode", "off", ConfigUpdateSource.BOOTSTRAP);
        } else if ("hmac".equalsIgnoreCase(securityMode)) {
            // HMAC bootstrap is an optional helper: it should not silently force-enable HMAC.
            if (config.isHmacBootstrapOnAttachEnabled()) {
                if (hmacSecret == null || hmacSecret.trim().isEmpty()) {
                    int bytes = config.getHmacBootstrapSecretBytes();
                    hmacSecret = generateHmacSecret(bytes);
                    config.setRuntimeConfig("security.hmac.secret", hmacSecret, ConfigUpdateSource.BOOTSTRAP);
                }
                config.setRuntimeConfig("security.hmac.session.role", hmacSessionRole, ConfigUpdateSource.BOOTSTRAP);
            }

            if (hmacSecret == null || hmacSecret.trim().isEmpty()) {
                return BuildResult.error(
                    "SECURITY ERROR: security.mode=hmac but empty security.hmac.secret\n" +
                        "Fix: set security.hmac.secret in configuration, or disable HMAC (security.mode=off)"
                );
            }
        }

        StringBuilder agentArgs = new StringBuilder();
        if (coreJar != null) {
            agentArgs.append("coreJar=").append(coreJar.getAbsolutePath()).append(";");
        }
        if (configFile != null && !configFile.trim().isEmpty()) {
            agentArgs.append("configFile=").append(configFile).append(";");
        }
        agentArgs.append("server.port=").append(config.getServerPort()).append(";");
        agentArgs.append("protocol.mode=").append(config.getProtocolMode()).append(";");
        agentArgs.append("protocol.streaming.enabled=").append(config.isStreamingEnabled()).append(";");
        agentArgs.append("security.mode=").append(securityMode).append(";");
        if ("hmac".equalsIgnoreCase(securityMode)) {
            agentArgs.append("security.hmac.secret=").append(hmacSecret).append(";");
            agentArgs.append("security.hmac.session.role=").append(hmacSessionRole).append(";");
        }

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

    private static String generateHmacSecret(int bytes) {
        int size = bytes <= 0 ? 32 : Math.min(bytes, 128);
        byte[] buf = new byte[size];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}

