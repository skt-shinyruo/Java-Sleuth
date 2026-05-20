package com.javasleuth.core.command.impl;

import com.javasleuth.core.agent.runtime.BootstrapMonitoringConfigSync;
import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.command.spec.SubcommandSpec;
import com.javasleuth.foundation.config.ConfigLoader;
import com.javasleuth.foundation.config.ConfigOrigin;
import com.javasleuth.foundation.config.ConfigUpdateSource;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.schema.ConfigKey;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.foundation.config.SensitiveKeyMasker;
import com.javasleuth.foundation.security.CommandMeta;

public class ConfigCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("config")
        .description("Manage production configuration settings")
        .usage("config [status|get|set|remove|save|clear|reload|show]")
        .meta(CommandMeta.admin(false, false))
        .subcommand(SubcommandSpec.of(
            "status",
            "Show configuration status",
            CommandSpec.builder("status")
                .description("Show configuration status")
                .usage("config status")
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "get",
            "Show one configuration key",
            CommandSpec.builder("get")
                .description("Show one configuration key")
                .usage("config get <key>")
                .argument(ArgumentSpec.required("key"))
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "set",
            "Set a runtime configuration override",
            CommandSpec.builder("set")
                .description("Set a runtime configuration override")
                .usage("config set <key> <value>")
                .argument(ArgumentSpec.required("key"))
                .argument(ArgumentSpec.required("value"))
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "remove",
            "Remove a runtime configuration override",
            CommandSpec.builder("remove")
                .description("Remove a runtime configuration override")
                .usage("config remove <key>")
                .argument(ArgumentSpec.required("key"))
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "save",
            "Save configuration to disk",
            CommandSpec.builder("save")
                .description("Save configuration to disk")
                .usage("config save [--include-runtime]")
                .option(OptionSpec.flag("include-runtime").alias("--include-overrides").build())
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "clear",
            "Clear runtime configuration overrides",
            CommandSpec.builder("clear")
                .description("Clear runtime configuration overrides")
                .usage("config clear")
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "reload",
            "Reload configuration from disk",
            CommandSpec.builder("reload")
                .description("Reload configuration from disk")
                .usage("config reload")
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "show",
            "Show the typed configuration snapshot",
            CommandSpec.builder("show")
                .description("Show the typed configuration snapshot")
                .usage("config show")
                .build()
        ))
        .example("config")
        .example("config get server.port")
        .example("config save --include-runtime")
        .build();

    private final ProductionConfig config;
    private final SensitiveKeyMasker masker = new SensitiveKeyMasker();

    public ConfigCommand(ProductionConfig config) {
        this.config = config;
    }

    public static CommandSpec spec() {
        return SPEC;
    }

    @Override
    public CommandSpec getSpec() {
        return SPEC;
    }

    @Override
    public String execute(String[] args) throws Exception {
        ParsedCommand parsed = CommandSpecSupport.parsed(SPEC, args);
        if (parsed.isHelpRequested()) {
            return CommandHelpRenderer.render(SPEC);
        }

        String action = parsed.subcommandName();
        if (action == null) {
            return renderStatus();
        }

        switch (action) {
            case "status":
                return renderStatus();

            case "get":
                String key = parsed.argument("key");
                ConfigKey<?> schemaKey = SleuthConfigSchema.byKey(key);
                if (schemaKey == null) {
                    return "Unknown config key: " + key;
                }
                Object typedValue = config.read(schemaKey);
                String value = typedValue == null ? "null" : String.valueOf(typedValue);
                String masked = masker.mask(key, value);
                return key + " = " + masked + " (origin=" + config.getOrigin(key) + ")";

            case "set":
                String setKey = parsed.argument("key");
                String setValue = parsed.argument("value");
                config.setRuntimeConfig(setKey, setValue, ConfigUpdateSource.COMMAND);
                BootstrapMonitoringConfigSync.syncFromProductionConfigBestEffort(config);
                return "Runtime configuration updated: " + setKey + " = " + masker.mask(setKey, setValue) +
                    " (origin=" + config.getOrigin(setKey) + ")";

            case "remove":
                String removeKey = parsed.argument("key");
                config.removeRuntimeConfig(removeKey, ConfigUpdateSource.COMMAND);
                BootstrapMonitoringConfigSync.syncFromProductionConfigBestEffort(config);
                return "Runtime configuration removed: " + removeKey;

            case "save":
                try {
                    boolean includeRuntime = Boolean.TRUE.equals(parsed.booleanOption("include-runtime"));
                    config.saveConfiguration(includeRuntime);
                    return includeRuntime
                        ? "Configuration saved successfully (including runtime overrides)"
                        : "Configuration saved to file successfully";
                } catch (Exception e) {
                    return "Failed to save configuration: " + e.getMessage();
                }

            case "clear":
                config.clearRuntimeConfig(ConfigUpdateSource.COMMAND);
                BootstrapMonitoringConfigSync.syncFromProductionConfigBestEffort(config);
                return "Runtime configuration cleared";

            case "reload":
                try {
                    boolean loaded = config.reloadConfiguration();
                    BootstrapMonitoringConfigSync.syncFromProductionConfigBestEffort(config);
                    String file = resolveLoadedConfigFile();
                    return loaded
                        ? "Configuration reloaded from file: " + file
                        : "Configuration reloaded (file not found, defaults used): " + file;
                } catch (Exception e) {
                    return "Failed to reload configuration: " + e.getMessage();
                }

            case "show":
                StringBuilder show = new StringBuilder();
                SleuthConfig typed = config.typedSnapshot();
                show.append("=== CURRENT CONFIGURATION ===\n");
                show.append("\n-- Server Settings --\n");
                show.append("server.bind.address = ").append(typed.server().getBindAddress()).append("\n");
                show.append("server.port = ").append(typed.server().getPort()).append("\n");
                show.append("server.max.connections = ").append(typed.server().getMaxConnections()).append("\n");
                show.append("server.executor.queue.capacity = ").append(typed.server().getExecutorQueueCapacity()).append("\n");
                show.append("server.connection.timeout = ").append(typed.server().getConnectionTimeoutMs()).append("\n");
                show.append("server.socket.timeout = ").append(typed.server().getSocketTimeoutMs()).append("\n");

                show.append("\n-- Performance Settings --\n");
                show.append("performance.cache.ttl = ").append(typed.performance().getCacheTtlMs()).append("\n");
                show.append("performance.thread.pool.core = ").append(typed.performance().getThreadPoolCoreSize()).append("\n");
                show.append("performance.thread.pool.max = ").append(typed.performance().getThreadPoolMaxSize()).append("\n");
                show.append("performance.command.executor.core = ").append(typed.performance().getCommandExecutorCoreSize()).append("\n");
                show.append("performance.command.executor.max = ").append(typed.performance().getCommandExecutorMaxSize()).append("\n");
                show.append("performance.command.executor.queue.capacity = ").append(typed.performance().getCommandExecutorQueueCapacity()).append("\n");
                show.append("performance.command.timeout = ").append(typed.performance().getCommandTimeoutMs()).append("\n");

                show.append("\n-- Jobs Settings --\n");
                show.append("jobs.max = ").append(typed.jobs().getMaxJobs()).append("\n");
                show.append("jobs.ttl.ms = ").append(typed.jobs().getTtlMs()).append("\n");
                show.append("jobs.output.max.bytes = ").append(typed.jobs().getOutputMaxBytes()).append("\n");
                show.append("jobs.max.running = ").append(typed.jobs().getMaxRunning()).append("\n");
                show.append("jobs.queue.capacity = ").append(typed.jobs().getQueueCapacity()).append("\n");

                show.append("\n-- Security Settings --\n");
                show.append("security.input.validation = ").append(typed.security().isInputValidationEnabled()).append("\n");
                show.append("security.audit.logging = ").append(typed.security().isAuditLoggingEnabled()).append("\n");
                show.append("security.authorization.enabled = ").append(typed.security().isAuthorizationEnabled()).append("\n");
                show.append("security.anonymous.viewer = ").append(typed.security().isAnonymousViewerEnabled()).append("\n");
                show.append("security.auth.password.enabled = ").append(typed.security().isPasswordAuthEnabled()).append("\n");
                show.append("security.dangerous.confirm.enabled = ").append(typed.security().isDangerousConfirmEnabled()).append("\n");
                show.append("security.dangerous.confirm.ttl.ms = ").append(typed.security().getDangerousConfirmTtlMs()).append("\n");
                show.append("security.impact.high.confirm.enabled = ").append(typed.security().isImpactHighConfirmEnabled()).append("\n");
                show.append("security.impact.high.concurrent.limit = ").append(typed.security().getImpactHighConcurrentLimit()).append("\n");
                show.append("security.max.command.length = ").append(typed.security().getMaxCommandLength()).append("\n");
                show.append("security.allowed.commands = ").append(typed.security().getAllowedCommands()).append("\n");

                show.append("\n-- Protocol Settings --\n");
                show.append("protocol.streaming.enabled = ").append(typed.protocol().isStreamingEnabled()).append("\n");
                show.append("protocol.frame.max.payload = ").append(typed.protocol().getFrameMaxPayloadBytes()).append("\n");
                show.append("protocol.text.max.line.bytes = ").append(typed.protocol().getTextMaxLineBytes()).append("\n");

                show.append("\n-- Plugin Settings --\n");
                show.append("plugins.enabled = ").append(typed.plugins().isEnabled()).append("\n");
                show.append("plugins.serviceloader.enabled = ").append(typed.plugins().isServiceLoaderEnabled()).append("\n");
                show.append("plugins.allowlist.sha256.configured = ")
                    .append(typed.plugins().getAllowlistSha256() != null && !typed.plugins().getAllowlistSha256().trim().isEmpty())
                    .append("\n");
                show.append("plugins.unsafe.allow-all-jars = ").append(typed.plugins().isUnsafeAllowAllJars()).append("\n");
                show.append("plugins.unsafe.legacy-provider-bridge.enabled = ")
                    .append(typed.plugins().isUnsafeLegacyProviderBridgeEnabled())
                    .append("\n");
                show.append("plugins.directory = ").append(typed.plugins().getDirectory()).append("\n");

                show.append("\n-- Monitoring Settings --\n");
                show.append("monitoring.metrics.enabled = ").append(typed.monitoring().isMetricsEnabled()).append("\n");
                show.append("monitoring.health.checks = ").append(typed.monitoring().areHealthChecksEnabled()).append("\n");
                show.append("monitoring.cache.cleanup.interval = ").append(typed.monitoring().getCacheCleanupIntervalMs()).append("\n");
                show.append("monitoring.jmx.enabled = ").append(typed.monitoring().isJmxEnabled()).append("\n");

                show.append("\n-- Logging Settings --\n");
                show.append("logging.level = ").append(typed.logging().getLevel()).append("\n");
                show.append("logging.audit.enabled = ").append(typed.logging().isAuditEnabled()).append("\n");
                show.append("logging.performance.enabled = ").append(typed.logging().isPerformanceEnabled()).append("\n");

                return show.toString();

            default:
                return CommandHelpRenderer.render(SPEC);
        }
    }

    @Override
    public String getDescription() {
        return "Manage production configuration settings (usage: config [status|get|set|remove|save|clear|reload|show])";
    }

    private String renderStatus() {
        SleuthConfig typed = config.typedSnapshot();
        int runtimeOverrides = countKnownRuntimeOverrides();
        StringBuilder status = new StringBuilder();
        status.append("=== CONFIGURATION STATUS ===\n");
        status.append("Config File: ").append(resolveLoadedConfigFile()).append("\n");
        status.append("Known Runtime Overrides: ").append(runtimeOverrides).append("\n");

        status.append("\n-- Key Settings --\n");
        status.append("Server: ").append(typed.server().getBindAddress()).append(":").append(typed.server().getPort()).append("\n");
        status.append("Max Connections: ").append(typed.server().getMaxConnections()).append("\n");
        status.append("Authorization: ").append(typed.security().isAuthorizationEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("Password Auth: ").append(typed.security().isPasswordAuthEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("Audit Logging: ").append(typed.security().isAuditLoggingEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("Metrics: ").append(typed.monitoring().isMetricsEnabled() ? "ENABLED" : "DISABLED").append("\n");
        return status.toString();
    }

    private int countKnownRuntimeOverrides() {
        int count = 0;
        for (ConfigKey<?> k : SleuthConfigSchema.keys()) {
            if (k == null) {
                continue;
            }
            if (config.getOrigin(k.getKey()) == ConfigOrigin.RUNTIME_OVERRIDE) {
                count++;
            }
        }
        return count;
    }

    private static String resolveConfigFile() {
        String explicit = System.getProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }
        return ConfigLoader.DEFAULT_CONFIG_FILE_NAME;
    }

    private String resolveLoadedConfigFile() {
        try {
            java.io.File f = config.getLoadedConfigFile();
            if (f != null) {
                return f.getAbsolutePath();
            }
        } catch (Exception ignore) {
            // ignore
        }
        return resolveConfigFile();
    }
}
