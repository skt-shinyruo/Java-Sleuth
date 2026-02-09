package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.security.SecurityValidator;

public class ConfigCommand implements Command {
    private final ProductionConfig config;

    public ConfigCommand(ProductionConfig config) {
        this.config = config;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length == 1) {
            return config.getConfigStatus();
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "status":
                return config.getConfigStatus();

            case "get":
                if (args.length < 3) {
                    return "Usage: config get <key>";
                }
                String key = args[2];
                String value = config.getString(key, "NOT_FOUND");
                return key + " = " + SecurityValidator.maskSensitiveValue(key, value);

            case "set":
                if (args.length < 4) {
                    return "Usage: config set <key> <value>";
                }
                String setKey = args[2];
                String setValue = args[3];
                config.setRuntimeConfig(setKey, setValue);
                return "Runtime configuration updated: " + setKey + " = " +
                    SecurityValidator.maskSensitiveValue(setKey, setValue);

            case "remove":
                if (args.length < 3) {
                    return "Usage: config remove <key>";
                }
                String removeKey = args[2];
                config.removeRuntimeConfig(removeKey);
                return "Runtime configuration removed: " + removeKey;

            case "save":
                try {
                    boolean includeRuntime = false;
                    if (args.length > 2) {
                        for (int i = 2; i < args.length; i++) {
                            String a = args[i];
                            if (a == null) {
                                continue;
                            }
                            String t = a.trim().toLowerCase();
                            if (t.isEmpty()) {
                                continue;
                            }
                            if ("--include-runtime".equals(t) || "--include-overrides".equals(t)) {
                                includeRuntime = true;
                            }
                        }
                    }
                    config.saveConfiguration(includeRuntime);
                    return includeRuntime
                        ? "Configuration saved successfully (including runtime overrides)"
                        : "Configuration saved to file successfully";
                } catch (Exception e) {
                    return "Failed to save configuration: " + e.getMessage();
                }

            case "clear":
                config.clearRuntimeConfig();
                return "Runtime configuration cleared";

            case "reload":
                // Note: In a real implementation, you might want to reload from file
                return "Configuration reload is not implemented (restart required)";

            case "show":
                StringBuilder show = new StringBuilder();
                show.append("=== CURRENT CONFIGURATION ===\n");
                show.append("\n-- Server Settings --\n");
                show.append("server.bind.address = ").append(config.getServerBindAddress()).append("\n");
                show.append("server.port = ").append(config.getServerPort()).append("\n");
                show.append("server.max.connections = ").append(config.getMaxConnections()).append("\n");
                show.append("server.executor.queue.capacity = ").append(config.getServerExecutorQueueCapacity()).append("\n");
                show.append("server.connection.timeout = ").append(config.getConnectionTimeout()).append("\n");
                show.append("server.socket.timeout = ").append(config.getSocketTimeout()).append("\n");

                show.append("\n-- Performance Settings --\n");
                show.append("performance.cache.ttl = ").append(config.getCacheTTL()).append("\n");
                show.append("performance.thread.pool.core = ").append(config.getThreadPoolCoreSize()).append("\n");
                show.append("performance.thread.pool.max = ").append(config.getThreadPoolMaxSize()).append("\n");
                show.append("performance.command.executor.core = ").append(config.getCommandExecutorCoreSize()).append("\n");
                show.append("performance.command.executor.max = ").append(config.getCommandExecutorMaxSize()).append("\n");
                show.append("performance.command.executor.queue.capacity = ").append(config.getCommandExecutorQueueCapacity()).append("\n");
                show.append("performance.command.timeout = ").append(config.getCommandTimeout()).append("\n");

                show.append("\n-- Jobs Settings --\n");
                show.append("jobs.max = ").append(config.getJobsMax()).append("\n");
                show.append("jobs.ttl.ms = ").append(config.getJobsTtlMs()).append("\n");
                show.append("jobs.output.max.bytes = ").append(config.getJobsOutputMaxBytes()).append("\n");
                show.append("jobs.max.running = ").append(config.getJobsMaxRunning()).append("\n");
                show.append("jobs.queue.capacity = ").append(config.getJobsQueueCapacity()).append("\n");

                show.append("\n-- Security Settings --\n");
                show.append("security.input.validation = ").append(config.isInputValidationEnabled()).append("\n");
                show.append("security.audit.logging = ").append(config.isAuditLoggingEnabled()).append("\n");
                show.append("security.authorization.enabled = ").append(config.isAuthorizationEnabled()).append("\n");
                show.append("security.anonymous.viewer = ").append(config.isAnonymousViewerEnabled()).append("\n");
                show.append("security.mode = ").append(config.getSecurityMode()).append("\n");
                String maskedSecret = SecurityValidator.maskSensitiveValue("security.hmac.secret", config.getSecurityHmacSecret());
                show.append("security.hmac.secret = ").append(maskedSecret).append("\n");
                show.append("security.hmac.secret.autogen.on.loopback = ").append(config.isHmacSecretAutogenOnLoopbackEnabled()).append("\n");
                show.append("security.hmac.secret.autogen.print = ").append(config.isHmacSecretAutogenPrintEnabled()).append("\n");
                show.append("security.impact.high.confirm.enabled = ").append(config.isHighImpactConfirmEnabled()).append("\n");
                show.append("security.impact.high.concurrent.limit = ").append(config.getHighImpactConcurrentLimit()).append("\n");
                show.append("security.max.command.length = ").append(config.getMaxCommandLength()).append("\n");
                show.append("security.allowed.commands = ").append(config.getAllowedCommands()).append("\n");

                show.append("\n-- Protocol Settings --\n");
                show.append("protocol.mode = ").append(config.getProtocolMode()).append("\n");
                show.append("protocol.streaming.enabled = ").append(config.isStreamingEnabled()).append("\n");
                show.append("protocol.frame.max.payload = ").append(config.getFrameMaxPayload()).append("\n");
                show.append("protocol.text.max.line.bytes = ").append(config.getInt("protocol.text.max.line.bytes", 8192)).append("\n");

                show.append("\n-- Plugin Settings --\n");
                show.append("plugins.enabled = ").append(config.isPluginsEnabled()).append("\n");
                show.append("plugins.serviceloader.enabled = ").append(config.isPluginsServiceLoaderEnabled()).append("\n");
                show.append("plugins.directory = ").append(config.getPluginDirectory()).append("\n");

                show.append("\n-- Monitoring Settings --\n");
                show.append("monitoring.metrics.enabled = ").append(config.isMetricsEnabled()).append("\n");
                show.append("monitoring.health.checks = ").append(config.areHealthChecksEnabled()).append("\n");
                show.append("monitoring.cache.cleanup.interval = ").append(config.getCacheCleanupInterval()).append("\n");
                show.append("monitoring.jmx.enabled = ").append(config.isJmxEnabled()).append("\n");

                show.append("\n-- Logging Settings --\n");
                show.append("logging.level = ").append(config.getLoggingLevel()).append("\n");
                show.append("logging.audit.enabled = ").append(config.isAuditLogEnabled()).append("\n");
                show.append("logging.performance.enabled = ").append(config.isPerformanceLogEnabled()).append("\n");

                return show.toString();

            default:
                return "Unknown config action: " + action + "\n" +
                       "Available actions: status, get, set, remove, save, clear, reload, show";
        }
    }

    @Override
    public String getDescription() {
        return "Manage production configuration settings (usage: config [status|get|set|remove|save|clear|show])";
    }
}
