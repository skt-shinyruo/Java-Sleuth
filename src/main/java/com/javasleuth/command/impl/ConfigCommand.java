package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.config.ProductionConfig;

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
                return key + " = " + value;

            case "set":
                if (args.length < 4) {
                    return "Usage: config set <key> <value>";
                }
                String setKey = args[2];
                String setValue = args[3];
                config.setRuntimeConfig(setKey, setValue);
                return "Runtime configuration updated: " + setKey + " = " + setValue;

            case "remove":
                if (args.length < 3) {
                    return "Usage: config remove <key>";
                }
                String removeKey = args[2];
                config.removeRuntimeConfig(removeKey);
                return "Runtime configuration removed: " + removeKey;

            case "save":
                try {
                    config.saveConfiguration();
                    return "Configuration saved to file successfully";
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
                show.append("server.port = ").append(config.getServerPort()).append("\n");
                show.append("server.max.connections = ").append(config.getMaxConnections()).append("\n");
                show.append("server.connection.timeout = ").append(config.getConnectionTimeout()).append("\n");
                show.append("server.socket.timeout = ").append(config.getSocketTimeout()).append("\n");

                show.append("\n-- Performance Settings --\n");
                show.append("performance.cache.ttl = ").append(config.getCacheTTL()).append("\n");
                show.append("performance.thread.pool.core = ").append(config.getThreadPoolCoreSize()).append("\n");
                show.append("performance.thread.pool.max = ").append(config.getThreadPoolMaxSize()).append("\n");
                show.append("performance.command.timeout = ").append(config.getCommandTimeout()).append("\n");

                show.append("\n-- Security Settings --\n");
                show.append("security.input.validation = ").append(config.isInputValidationEnabled()).append("\n");
                show.append("security.audit.logging = ").append(config.isAuditLoggingEnabled()).append("\n");
                show.append("security.max.command.length = ").append(config.getMaxCommandLength()).append("\n");
                show.append("security.allowed.commands = ").append(config.getAllowedCommands()).append("\n");

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