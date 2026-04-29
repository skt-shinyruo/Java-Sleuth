package com.javasleuth.core.command;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CommandRegistry {
    public static class Entry {
        private final Command command;
        private final CommandMeta meta;
        private final String source;
        private final String canonicalName;
        private final String namespace;
        private final CommandSpec spec;

        public Entry(Command command, CommandMeta meta, String source) {
            this(command, meta, source, null, null);
        }

        public Entry(Command command, CommandMeta meta, String source, String canonicalName, String namespace) {
            this(command, meta, source, canonicalName, namespace, null);
        }

        public Entry(Command command, CommandMeta meta, String source, String canonicalName, String namespace, CommandSpec spec) {
            this.command = command;
            this.meta = meta;
            this.source = source;
            this.canonicalName = canonicalName;
            this.namespace = namespace;
            this.spec = spec;
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

        public String getCanonicalName() {
            return canonicalName;
        }

        public String getNamespace() {
            return namespace;
        }

        public CommandSpec getSpec() {
            return spec;
        }
    }

    private final ConcurrentHashMap<String, Entry> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Command> commandView = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CommandProviderInfo> providerInfoByNamespace = new ConcurrentHashMap<>();
    private final ProductionConfig config;
    private final MetricsCollector metricsCollector;
    private final AuditLogger auditLogger;
    private final CommandProviderContext providerContext;
    private volatile URLClassLoader pluginClassLoader;

    public CommandRegistry(
        ProductionConfig config,
        MetricsCollector metricsCollector,
        AuditLogger auditLogger,
        Collection<CommandProvider> providers,
        URLClassLoader pluginClassLoader,
        CommandProviderContext providerContext
    ) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (auditLogger == null) {
            throw new IllegalArgumentException("auditLogger is required");
        }
        if (providerContext == null) {
            throw new IllegalArgumentException("providerContext is required");
        }
        this.config = config;
        this.metricsCollector = metricsCollector;
        this.auditLogger = auditLogger;
        this.providerContext = providerContext;
        this.pluginClassLoader = pluginClassLoader;
        registerProviders(providers);
        registerHelpCommand();
    }

    public Entry getEntry(String commandName) {
        return registry.get(commandName.toLowerCase());
    }

    public Map<String, Command> getCommandMap() {
        return Collections.unmodifiableMap(commandView);
    }

    public Collection<Entry> listEntries() {
        return Collections.unmodifiableCollection(new LinkedHashSet<Entry>(registry.values()));
    }

    public Collection<CommandProviderInfo> listProviderInfos() {
        return Collections.unmodifiableCollection(new ArrayList<CommandProviderInfo>(providerInfoByNamespace.values()));
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
            providerInfoByNamespace.clear();
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
        registerDescriptor(
            CommandProviderInfo.builtin("builtin", Collections.singleton("help")),
            CommandDescriptor.of("help", help, CommandMeta.viewer(true, false))
        );
    }

    private void registerProviders(Collection<CommandProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return;
        }

        for (CommandProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            CommandProviderInfo providerInfo = validateAndRegisterProviderInfo(provider);
            if (providerInfo == null) {
                continue;
            }

            if (metricsCollector != null && !providerInfo.isBuiltin()) {
                metricsCollector.recordPluginProviderLoaded();
            }

            Collection<CommandDescriptor> descriptors = provider.getCommandDescriptors(providerContext);
            if (descriptors == null || descriptors.isEmpty()) {
                continue;
            }
            for (CommandDescriptor descriptor : descriptors) {
                if (descriptor == null) {
                    continue;
                }
                registerDescriptor(providerInfo, descriptor);
            }
        }
    }

    private CommandProviderInfo validateAndRegisterProviderInfo(CommandProvider provider) {
        String providerName = provider != null ? provider.getName() : null;
        if (providerName == null || providerName.trim().isEmpty()) {
            providerName = "<unknown>";
        }
        CommandProviderInfo rawInfo = provider != null ? provider.getInfo() : null;
        CommandProviderInfo info = rawInfo != null ? rawInfo : CommandProviderInfo.legacy(providerName);
        if (!info.isApiVersionSupported()) {
            if (info.isBuiltin()) {
                throw new IllegalStateException("Builtin provider API version is not supported: " + info.getApiVersion());
            }
            logPluginRejected(
                "PLUGIN_API_VERSION_UNSUPPORTED",
                "Rejected provider with unsupported API version: provider=" + info.getProviderName()
                    + ", namespace=" + info.getNamespace()
                    + ", version=" + info.getApiVersion()
            );
            return null;
        }
        CommandProviderInfo existing = providerInfoByNamespace.putIfAbsent(info.getNamespace(), info);
        if (existing != null) {
            if (info.isBuiltin()) {
                throw new IllegalStateException("Duplicate builtin provider namespace: " + info.getNamespace());
            }
            logPluginRejected(
                "PLUGIN_NAMESPACE_CONFLICT",
                "Rejected provider with duplicate namespace: provider=" + info.getProviderName()
                    + ", namespace=" + info.getNamespace()
            );
            return null;
        }
        return info;
    }

    private void registerDescriptor(CommandProviderInfo providerInfo, CommandDescriptor descriptor) {
        String name = normalizeCommandName(descriptor != null ? descriptor.getName() : null);
        if (name == null) {
            return;
        }
        Command command = descriptor.getCommand();
        if (command == null) {
            return;
        }
        CommandMeta meta = descriptor.getMeta();
        if (meta == null) {
            if (providerInfo.isBuiltin()) {
                throw new IllegalStateException("Builtin command missing metadata: " + name);
            }
            logPluginRejected(
                "PLUGIN_META_MISSING",
                "Rejected command without meta: provider=" + providerInfo.getProviderName() + ", command=" + name
            );
            return;
        }

        String canonicalName = providerInfo.isBuiltin() ? name : providerInfo.getNamespace() + ":" + name;
        Entry entry = new Entry(command, meta, providerInfo.getProviderName(), canonicalName, providerInfo.getNamespace(), descriptor.getSpec());
        registerLookup(canonicalName, entry, true, !providerInfo.isBuiltin());
        if (!providerInfo.isBuiltin() && providerInfo.isExposeUnqualifiedCommands() && !name.equals(canonicalName)) {
            registerLookup(name, entry, false, false);
        }
    }

    private static String normalizeCommandName(String rawName) {
        if (rawName == null) {
            return null;
        }
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        return name.isEmpty() ? null : name;
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

    private void registerLookup(String name, Entry newEntry, boolean exposeInCommandView, boolean countPluginMetric) {
        Entry existing = registry.get(name);
        if (existing == null) {
            registry.put(name, newEntry);
            if (exposeInCommandView) {
                commandView.put(name, newEntry.getCommand());
            }
            if (metricsCollector != null && countPluginMetric) {
                metricsCollector.recordPluginCommandRegistered();
            }
            return;
        }

        CommandConflictStrategy strategy =
            CommandConflictStrategy.fromConfig(SleuthConfigSchema.PLUGINS_CONFLICT_STRATEGY.read(config));

        boolean incomingIsBuiltin = "builtin".equalsIgnoreCase(newEntry.getNamespace()) || "builtin".equalsIgnoreCase(newEntry.getSource());
        boolean existingIsBuiltin = "builtin".equalsIgnoreCase(existing.getSource());

        if (strategy == CommandConflictStrategy.FAIL) {
            return;
        }

        if (strategy == CommandConflictStrategy.PREFER_PLUGIN) {
            if (!incomingIsBuiltin || existingIsBuiltin) {
                registry.put(name, newEntry);
                if (exposeInCommandView) {
                    commandView.put(name, newEntry.getCommand());
                }
                if (metricsCollector != null && countPluginMetric) {
                    metricsCollector.recordPluginCommandRegistered();
                }
            }
            return;
        }

        // Default: prefer builtin.
        if (existingIsBuiltin && !incomingIsBuiltin) {
            return;
        }
        registry.put(name, newEntry);
        if (exposeInCommandView) {
            commandView.put(name, newEntry.getCommand());
        }
        if (metricsCollector != null && countPluginMetric) {
            metricsCollector.recordPluginCommandRegistered();
        }
    }
}
