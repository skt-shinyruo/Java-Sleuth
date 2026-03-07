package com.javasleuth.bootstrap.util;

/**
 * Shared agentArgs parsing + System properties application.
 *
 * <p>Both bootstrap ({@code com.javasleuth.agent.SleuthAgent}) and isolated entrypoints
 * ({@code com.javasleuth.container.SleuthAgentContainerEntrypoint}) must interpret agentArgs
 * consistently to avoid config drift.</p>
 *
 * <p>Format: {@code k=v;k2=v2;...} (semicolon separated). Keys without {@code sleuth.}
 * prefix are automatically namespaced as {@code sleuth.<key>}.</p>
 */
public final class AgentArgsApplier {
    private static final String SLEUTH_PREFIX = "sleuth.";

    private AgentArgsApplier() {}

    public static void applyToSystemProperties(String agentArgs) {
        applyToSystemPropertiesInternal(agentArgs, null);
    }

    /**
     * Apply agentArgs to System properties and return a rollback handle.
     *
     * <p>Caller should keep the returned handle and invoke {@link SystemPropertyRollback#rollbackBestEffort()}
     * on detach/shutdown to restore original values.</p>
     */
    public static SystemPropertyRollback applyToSystemPropertiesWithRollback(String agentArgs) {
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return SystemPropertyRollback.noop();
        }
        SystemPropertyRollback rollback = new SystemPropertyRollback();
        applyToSystemPropertiesInternal(agentArgs, rollback);
        return rollback;
    }

    private static void applyToSystemPropertiesInternal(String agentArgs, SystemPropertyRollback rollback) {
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
                if (rollback != null) {
                    rollback.recordBeforeSet("sleuth.config.file");
                }
                System.setProperty("sleuth.config.file", value);
                continue;
            }

            if ("containerJar".equalsIgnoreCase(key)) {
                if (rollback != null) {
                    rollback.recordBeforeSet(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY);
                }
                System.setProperty(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY, value);
                continue;
            }

            if (key.startsWith(SLEUTH_PREFIX)) {
                if (rollback != null) {
                    rollback.recordBeforeSet(key);
                }
                System.setProperty(key, value);
                continue;
            }

            if (rollback != null) {
                rollback.recordBeforeSet(SLEUTH_PREFIX + key);
            }
            System.setProperty(SLEUTH_PREFIX + key, value);
        }
    }
}
