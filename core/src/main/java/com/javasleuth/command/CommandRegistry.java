package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitoring.MetricsCollector;
import com.javasleuth.security.AuditLogger;
import com.javasleuth.security.AuthenticationManager;
import com.javasleuth.security.CommandMeta;
import com.javasleuth.security.DangerousCommandConfirmationManager;
import com.javasleuth.util.SleuthLogger;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.ServiceConfigurationError;
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
    private final Runnable shutdownHook;
    private final AuthenticationManager authenticationManager;
    private final DangerousCommandConfirmationManager dangerousConfirm;
    private volatile URLClassLoader pluginClassLoader;

    public CommandRegistry(Instrumentation instrumentation,
                           SleuthClassFileTransformer transformer,
                           MetricsCollector metricsCollector,
                           ProductionConfig config,
                           AuditLogger auditLogger,
                           Runnable shutdownHook) {
        this(instrumentation, transformer, metricsCollector, config, auditLogger, shutdownHook,
            AuthenticationManager.getInstance(), DangerousCommandConfirmationManager.getInstance());
    }

    public CommandRegistry(Instrumentation instrumentation,
                           SleuthClassFileTransformer transformer,
                           MetricsCollector metricsCollector,
                           ProductionConfig config,
                           AuditLogger auditLogger,
                           Runnable shutdownHook,
                           AuthenticationManager authenticationManager,
                           DangerousCommandConfirmationManager dangerousConfirm) {
        this.config = config;
        this.metricsCollector = metricsCollector;
        this.auditLogger = auditLogger;
        this.shutdownHook = shutdownHook;
        this.authenticationManager = authenticationManager != null ? authenticationManager : AuthenticationManager.getInstance();
        this.dangerousConfirm = dangerousConfirm != null ? dangerousConfirm : DangerousCommandConfirmationManager.getInstance();
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
        Command help = new com.javasleuth.command.impl.HelpCommand(commandView);
        register("help", help, CommandMeta.viewer(true, false), "builtin");
    }

    private void loadProviders(Instrumentation instrumentation,
                               SleuthClassFileTransformer transformer,
                               MetricsCollector metricsCollector,
                               AuditLogger auditLogger) {
        List<CommandProvider> providers = new ArrayList<>();
        providers.add(new BuiltinCommandProvider(
            instrumentation,
            transformer,
            metricsCollector,
            config,
            auditLogger,
            shutdownHook,
            authenticationManager,
            dangerousConfirm
        ));

        if (config.isPluginsServiceLoaderEnabled()) {
            ServiceLoader<CommandProvider> loader = ServiceLoader.load(CommandProvider.class);
            for (CommandProvider provider : loader) {
                providers.add(provider);
            }
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
                CommandMeta meta = metas != null ? metas.get(name) : null;
                if (meta == null) {
                    if ("builtin".equalsIgnoreCase(provider.getName())) {
                        meta = CommandMeta.viewer(false, false);
                    } else {
                        logPluginRejected("PLUGIN_META_MISSING",
                            "Rejected command without meta: provider=" + provider.getName() + ", command=" + name);
                        continue;
                    }
                }
                register(name, entry.getValue(), meta, provider.getName());
            }
        }
    }

    private List<CommandProvider> loadFromPluginDirectory() {
        if (!config.isPluginsEnabled()) {
            return Collections.emptyList();
        }

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
            Map<String, String> allowlist = parseSha256Allowlist(config.getPluginsAllowlistSha256());
            List<URL> urls = new ArrayList<>();
            for (File jar : jars) {
                if (!allowlist.isEmpty()) {
                    String expected = allowlist.get(jar.getName());
                    if (expected == null || expected.trim().isEmpty()) {
                        logPluginRejected("PLUGIN_NOT_ALLOWLISTED",
                            "Rejected plugin jar (not in allowlist): " + jar.getAbsolutePath());
                        continue;
                    }
                    String actual = sha256Hex(jar);
                    if (actual == null) {
                        logPluginRejected("PLUGIN_SHA256_FAILED",
                            "Rejected plugin jar (sha256 compute failed): " + jar.getAbsolutePath());
                        continue;
                    }
                    if (!actual.equalsIgnoreCase(expected.trim())) {
                        logPluginRejected("PLUGIN_SHA256_MISMATCH",
                            "Rejected plugin jar (sha256 mismatch): " + jar.getAbsolutePath());
                        continue;
                    }
                }
                urls.add(jar.toURI().toURL());
            }
            if (urls.isEmpty()) {
                return Collections.emptyList();
            }

            URLClassLoader loader = null;
            try {
                loader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
                ServiceLoader<CommandProvider> sl = ServiceLoader.load(CommandProvider.class, loader);
                List<CommandProvider> providers = new ArrayList<>();
                for (CommandProvider p : sl) {
                    providers.add(p);
                }
                pluginClassLoader = loader;
                return providers;
            } catch (ServiceConfigurationError e) {
                SleuthLogger.warn("Plugin ServiceLoader configuration error: " + e.getMessage());
                return Collections.emptyList();
            } catch (RuntimeException e) {
                SleuthLogger.warn("Plugin ServiceLoader failed: " + e.getMessage(), e);
                return Collections.emptyList();
            } finally {
                if (loader != null && loader != pluginClassLoader) {
                    try {
                        loader.close();
                    } catch (IOException e) {
                        SleuthLogger.debug("Failed to close failed plugin loader (ignored): " + e.getMessage(), e);
                    }
                }
            }
        } catch (IOException e) {
            SleuthLogger.warn("Failed to load plugins from directory: " + e.getMessage(), e);
            return Collections.emptyList();
        } catch (RuntimeException e) {
            SleuthLogger.warn("Failed to load plugins from directory: " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private static Map<String, String> parseSha256Allowlist(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return out;
        }
        String[] entries = v.split(",");
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            String t = entry.trim();
            if (t.isEmpty()) {
                continue;
            }
            int idx = t.indexOf(':');
            if (idx <= 0 || idx >= t.length() - 1) {
                continue;
            }
            String name = t.substring(0, idx).trim();
            String sha = t.substring(idx + 1).trim();
            if (!name.isEmpty() && !sha.isEmpty()) {
                out.put(name, sha);
            }
        }
        return out;
    }

    private static String sha256Hex(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException | SecurityException e) {
            SleuthLogger.debug("Failed to compute sha256 for " + file.getAbsolutePath() + ": " + e.getMessage(), e);
            return null;
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
            if (metricsCollector != null && !"builtin".equalsIgnoreCase(source)) {
                metricsCollector.recordPluginCommandRegistered();
            }
        }
    }
}
