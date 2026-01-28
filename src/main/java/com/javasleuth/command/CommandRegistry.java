package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthorizationManager;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
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

    public CommandRegistry(Instrumentation instrumentation,
                           SleuthClassFileTransformer transformer,
                           MetricsCollector metricsCollector,
                           ProductionConfig config,
                           AuditLogger auditLogger) {
        this.config = config;
        this.metricsCollector = metricsCollector;
        loadProviders(instrumentation, transformer, metricsCollector, auditLogger);
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

    private void registerHelpCommand() {
        Command help = new com.javasleuth.command.impl.HelpCommand(commandView);
        register("help", help, CommandMeta.viewer(true, false), "builtin");
    }

    private void loadProviders(Instrumentation instrumentation,
                               SleuthClassFileTransformer transformer,
                               MetricsCollector metricsCollector,
                               AuditLogger auditLogger) {
        List<CommandProvider> providers = new ArrayList<>();
        providers.add(new BuiltinCommandProvider(instrumentation, transformer, metricsCollector, config, auditLogger));

        ServiceLoader<CommandProvider> loader = ServiceLoader.load(CommandProvider.class);
        for (CommandProvider provider : loader) {
            providers.add(provider);
        }

        providers.addAll(loadFromPluginDirectory());

        for (CommandProvider provider : providers) {
            if (metricsCollector != null && !"builtin".equalsIgnoreCase(provider.getName())) {
                metricsCollector.recordPluginProviderLoaded();
            }
            Map<String, Command> commands = provider.getCommands();
            Map<String, CommandMeta> metas = provider.getCommandMeta();
            for (Map.Entry<String, Command> entry : commands.entrySet()) {
                String name = entry.getKey().toLowerCase();
                CommandMeta meta = metas.getOrDefault(name, CommandMeta.viewer(false, false));
                register(name, entry.getValue(), meta, provider.getName());
            }
        }
    }

    private List<CommandProvider> loadFromPluginDirectory() {
        String dirPath = config.getPluginDirectory();
        if (dirPath == null || dirPath.trim().isEmpty()) {
            return Collections.emptyList();
        }

        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return Collections.emptyList();
        }

        try {
            List<URL> urls = new ArrayList<>();
            for (File jar : jars) {
                urls.add(jar.toURI().toURL());
            }
            URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
            ServiceLoader<CommandProvider> sl = ServiceLoader.load(CommandProvider.class, loader);
            List<CommandProvider> providers = new ArrayList<>();
            for (CommandProvider p : sl) {
                providers.add(p);
            }
            return providers;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private void register(String name, Command command, CommandMeta meta, String source) {
        Entry existing = registry.get(name);
        if (existing == null) {
            registry.put(name, new Entry(command, meta, source));
            commandView.put(name, command);
            AuthorizationManager.getInstance().registerOrUpdatePermission(name, meta);
            if (metricsCollector != null && !"builtin".equalsIgnoreCase(source)) {
                metricsCollector.recordPluginCommandRegistered();
            }
            return;
        }

        String strategy = config.getPluginConflictStrategy();
        boolean preferPlugin = "prefer-plugin".equalsIgnoreCase(strategy);
        boolean preferBuiltin = "prefer-builtin".equalsIgnoreCase(strategy) || strategy == null;
        boolean fail = "fail".equalsIgnoreCase(strategy);

        boolean incomingIsBuiltin = "builtin".equalsIgnoreCase(source);
        boolean existingIsBuiltin = "builtin".equalsIgnoreCase(existing.getSource());

        if (fail) {
            return;
        }

        if (preferPlugin) {
            if (!incomingIsBuiltin || existingIsBuiltin) {
                registry.put(name, new Entry(command, meta, source));
                commandView.put(name, command);
                AuthorizationManager.getInstance().registerOrUpdatePermission(name, meta);
                if (metricsCollector != null && !"builtin".equalsIgnoreCase(source)) {
                    metricsCollector.recordPluginCommandRegistered();
                }
            }
            return;
        }

        if (preferBuiltin) {
            if (existingIsBuiltin && !incomingIsBuiltin) {
                return;
            }
            registry.put(name, new Entry(command, meta, source));
            commandView.put(name, command);
            AuthorizationManager.getInstance().registerOrUpdatePermission(name, meta);
            if (metricsCollector != null && !"builtin".equalsIgnoreCase(source)) {
                metricsCollector.recordPluginCommandRegistered();
            }
        }
    }
}
