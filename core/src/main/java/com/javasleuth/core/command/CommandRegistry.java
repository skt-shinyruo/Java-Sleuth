package com.javasleuth.core.command;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CommandRegistry {
    public static class Entry {
        private final Command command;
        private final CommandMeta meta;
        private final String source;

        public Entry(Command command, CommandMeta meta, String source) {
            this.command = command;
            this.meta = meta;
            this.source = source;
        }

        public Command getCommand() {
            return command;
        }

        public CommandMeta getMeta() {
            return meta;
        }

        public String getSource() {
            return source;
        }
    }

    private final ConcurrentHashMap<String, Entry> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Command> commandView = new ConcurrentHashMap<>();
    private final ProductionConfig config;
    private final MetricsCollector metricsCollector;
    private final AuditLogger auditLogger;
    private volatile URLClassLoader pluginClassLoader;

    public CommandRegistry(
        ProductionConfig config,
        MetricsCollector metricsCollector,
        AuditLogger auditLogger,
        Collection<CommandProvider> providers,
        URLClassLoader pluginClassLoader
    ) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (auditLogger == null) {
            throw new IllegalArgumentException("auditLogger is required");
        }
        this.config = config;
        this.metricsCollector = metricsCollector;
        this.auditLogger = auditLogger;
        this.pluginClassLoader = pluginClassLoader;
        registerProviders(providers);
        registerHelpCommand();
    }

    public Entry getEntry(String commandName) {
        return registry.get(commandName.toLowerCase());
    }

    public Map<String, Command> getCommandMap() {
        return commandView;
    }

    public Collection<Entry> listEntries() {
        return Collections.unmodifiableCollection(registry.values());
    }

    public void shutdown() {
        // Best-effort: close commands that own background resources (timers/executors/files).
        // We intentionally do this before closing pluginClassLoader so that plugin commands can
        // still execute their cleanup logic with classes available.
        try {
            for (Entry e : registry.values()) {
                if (e == null) {
                    continue;
                }
                Command c = e.getCommand();
                if (!(c instanceof AutoCloseable)) {
                    continue;
                }
                try {
                    ((AutoCloseable) c).close();
                } catch (Exception ex) {
                    SleuthLogger.debug("Command close() failed (ignored): cmd=" + safeName(c) + ", err=" + ex.getMessage(), ex);
                } catch (Throwable t) {
                    SleuthLogger.debug("Command close() failed (ignored): cmd=" + safeName(c) + ", err=" + t.getMessage(), t);
                }
            }
        } catch (Exception ignore) {
            // ignore
        }

        URLClassLoader loader = pluginClassLoader;
        pluginClassLoader = null;
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException e) {
                SleuthLogger.debug("Failed to close plugin classloader (ignored): " + e.getMessage(), e);
            }
        }

        try {
            registry.clear();
        } catch (Exception ignore) {
            // ignore
        }
        try {
            commandView.clear();
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static String safeName(Command c) {
        if (c == null) {
            return "<null>";
        }
        try {
            return c.getClass().getName();
        } catch (Exception ignore) {
            return "<unknown>";
        }
    }

    private void registerHelpCommand() {
        Command help = new com.javasleuth.core.command.impl.HelpCommand(commandView);
        register("help", help, CommandMeta.viewer(true, false), "builtin");
    }

    private void registerProviders(Collection<CommandProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return;
        }

        for (CommandProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            String providerName = provider.getName();
            if (providerName == null || providerName.trim().isEmpty()) {
                providerName = "<unknown>";
            }

            if (metricsCollector != null && !"builtin".equalsIgnoreCase(providerName)) {
                metricsCollector.recordPluginProviderLoaded();
            }

            Map<String, Command> commands = provider.getCommands();
            if (commands == null || commands.isEmpty()) {
                continue;
            }
            Map<String, CommandMeta> metas = provider.getCommandMeta();
            for (Map.Entry<String, Command> entry : commands.entrySet()) {
                if (entry == null) {
                    continue;
                }
                String rawName = entry.getKey();
                if (rawName == null) {
                    continue;
                }
                String name = rawName.trim().toLowerCase();
                if (name.isEmpty()) {
                    continue;
                }

                Command cmd = entry.getValue();
                if (cmd == null) {
                    continue;
                }

                CommandMeta meta = metas != null ? metas.get(name) : null;
                if (meta == null) {
                    if ("builtin".equalsIgnoreCase(providerName)) {
                        meta = CommandMeta.viewer(false, false);
                    } else {
                        logPluginRejected(
                            "PLUGIN_META_MISSING",
                            "Rejected command without meta: provider=" + providerName + ", command=" + name
                        );
                        continue;
                    }
                }
                register(name, cmd, meta, providerName);
            }
        }
    }

    private void logPluginRejected(String violation, String details) {
        if (auditLogger == null) {
            return;
        }
        try {
            auditLogger.logSecurityViolation(null, "plugin-loader", violation, details);
        } catch (RuntimeException e) {
            SleuthLogger.debug("Failed to write plugin rejection audit (ignored): " + e.getMessage(), e);
        }
    }

    private void register(String name, Command command, CommandMeta meta, String source) {
        Entry existing = registry.get(name);
        if (existing == null) {
            registry.put(name, new Entry(command, meta, source));
            commandView.put(name, command);
            if (metricsCollector != null && !"builtin".equalsIgnoreCase(source)) {
                metricsCollector.recordPluginCommandRegistered();
            }
            return;
        }

        CommandConflictStrategy strategy =
            CommandConflictStrategy.fromConfig(SleuthConfigSchema.PLUGINS_CONFLICT_STRATEGY.read(config));

        boolean incomingIsBuiltin = "builtin".equalsIgnoreCase(source);
        boolean existingIsBuiltin = "builtin".equalsIgnoreCase(existing.getSource());

        if (strategy == CommandConflictStrategy.FAIL) {
            return;
        }

        if (strategy == CommandConflictStrategy.PREFER_PLUGIN) {
            if (!incomingIsBuiltin || existingIsBuiltin) {
                registry.put(name, new Entry(command, meta, source));
                commandView.put(name, command);
                if (metricsCollector != null && !"builtin".equalsIgnoreCase(source)) {
                    metricsCollector.recordPluginCommandRegistered();
                }
            }
            return;
        }

        // Default: prefer builtin.
        if (existingIsBuiltin && !incomingIsBuiltin) {
            return;
        }
        registry.put(name, new Entry(command, meta, source));
        commandView.put(name, command);
        if (metricsCollector != null && !"builtin".equalsIgnoreCase(source)) {
            metricsCollector.recordPluginCommandRegistered();
        }
    }
}
