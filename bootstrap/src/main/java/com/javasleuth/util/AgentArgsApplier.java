package com.javasleuth.util;

/**
 * Shared agentArgs parsing + System properties application.
 *
 * <p>Both bootstrap ({@code com.javasleuth.agent.SleuthAgent}) and core
 * ({@code com.javasleuth.agent.core.SleuthAgentCore}) must interpret agentArgs
 * consistently to avoid config drift.</p>
 *
 * <p>Format: {@code k=v;k2=v2;...} (semicolon separated). Keys without {@code sleuth.}
 * prefix are automatically namespaced as {@code sleuth.<key>}.</p>
 */
public final class AgentArgsApplier {
    private static final String SLEUTH_PREFIX = "sleuth.";

    private AgentArgsApplier() {}

    public static void applyToSystemProperties(String agentArgs) {
        if (agentArgs == null) {
            return;
        }
        String trimmedAll = agentArgs.trim();
        if (trimmedAll.isEmpty()) {
            return;
        }

        String[] pairs = trimmedAll.split(";");
        for (String pair : pairs) {
            if (pair == null) {
                continue;
            }
            String trimmed = pair.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }

            String[] kv = trimmed.split("=", 2);
            String key = kv[0] != null ? kv[0].trim() : "";
            String value = kv.length > 1 && kv[1] != null ? kv[1].trim() : "";
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }

            if ("configFile".equalsIgnoreCase(key)) {
                System.setProperty("sleuth.config.file", value);
                continue;
            }

            if ("coreJar".equalsIgnoreCase(key)) {
                System.setProperty(JarLocator.AGENT_CORE_JAR_OVERRIDE_PROPERTY, value);
                continue;
            }

            if (key.startsWith(SLEUTH_PREFIX)) {
                System.setProperty(key, value);
                continue;
            }

            System.setProperty(SLEUTH_PREFIX + key, value);
        }
    }
}

