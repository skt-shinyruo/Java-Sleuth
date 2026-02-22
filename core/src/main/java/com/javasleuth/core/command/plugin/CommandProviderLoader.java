package com.javasleuth.core.command.plugin;

import com.javasleuth.core.command.CommandProvider;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * 命令 provider 加载器：负责从 classpath / ServiceLoader / 插件目录加载 CommandProvider。
 *
 * <p>注意：该类只负责 provider 发现与插件供应链校验（allowlist + sha256），不负责命令注册冲突策略。</p>
 */
public final class CommandProviderLoader {
    public static final class LoadedProviders {
        private final List<CommandProvider> providers;
        private final URLClassLoader pluginClassLoader;

        private LoadedProviders(List<CommandProvider> providers, URLClassLoader pluginClassLoader) {
            this.providers = providers != null ? providers : Collections.<CommandProvider>emptyList();
            this.pluginClassLoader = pluginClassLoader;
        }

        public List<CommandProvider> getProviders() {
            return providers;
        }

        /**
         * 插件目录加载所使用的 classloader（用于 detach 时关闭）。
         *
         * <p>当 plugins.enabled=false 或未加载到任何插件时为 null。</p>
         */
        public URLClassLoader getPluginClassLoader() {
            return pluginClassLoader;
        }
    }

    private final ConfigView config;
    private final AuditLogger auditLogger;
    private final ClassLoader parentClassLoader;

    public CommandProviderLoader(ConfigView config, AuditLogger auditLogger, ClassLoader parentClassLoader) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (auditLogger == null) {
            throw new IllegalArgumentException("auditLogger is required");
        }
        this.config = config;
        this.auditLogger = auditLogger;
        this.parentClassLoader = parentClassLoader != null ? parentClassLoader : CommandProviderLoader.class.getClassLoader();
    }

    public LoadedProviders load(CommandProvider builtinProvider) {
        List<CommandProvider> providers = new ArrayList<>();
        if (builtinProvider != null) {
            providers.add(builtinProvider);
        }

        if (SleuthConfigSchema.PLUGINS_SERVICELOADER_ENABLED.read(config)) {
            providers.addAll(loadFromServiceLoader());
        }

        LoadedProviders plugins = loadFromPluginDirectory();
        if (plugins.getProviders() != null && !plugins.getProviders().isEmpty()) {
            providers.addAll(plugins.getProviders());
        }
        return new LoadedProviders(providers, plugins.getPluginClassLoader());
    }

    private List<CommandProvider> loadFromServiceLoader() {
        try {
            List<CommandProvider> providers = new ArrayList<>();
            ServiceLoader<CommandProvider> loader = ServiceLoader.load(CommandProvider.class, parentClassLoader);
            for (CommandProvider p : loader) {
                if (p != null) {
                    providers.add(p);
                }
            }
            return providers;
        } catch (ServiceConfigurationError e) {
            SleuthLogger.warn("Plugin ServiceLoader configuration error: " + e.getMessage());
            return Collections.emptyList();
        } catch (RuntimeException e) {
            SleuthLogger.warn("Plugin ServiceLoader failed: " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private LoadedProviders loadFromPluginDirectory() {
        if (!SleuthConfigSchema.PLUGINS_ENABLED.read(config)) {
            return new LoadedProviders(Collections.<CommandProvider>emptyList(), null);
        }

        String dirPath = SleuthConfigSchema.PLUGINS_DIRECTORY.read(config);
        if (dirPath == null || dirPath.trim().isEmpty()) {
            return new LoadedProviders(Collections.<CommandProvider>emptyList(), null);
        }

        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return new LoadedProviders(Collections.<CommandProvider>emptyList(), null);
        }

        File[] jars = dir.listFiles((d, name) -> name != null && name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return new LoadedProviders(Collections.<CommandProvider>emptyList(), null);
        }

        try {
            Map<String, String> allowlist = parseSha256Allowlist(SleuthConfigSchema.PLUGINS_ALLOWLIST_SHA256.read(config));
            List<URL> urls = new ArrayList<>();
            for (File jar : jars) {
                if (jar == null) {
                    continue;
                }
                if (!allowlist.isEmpty()) {
                    String expected = allowlist.get(jar.getName());
                    if (expected == null || expected.trim().isEmpty()) {
                        logPluginRejected("PLUGIN_NOT_ALLOWLISTED", "Rejected plugin jar (not in allowlist): " + jar.getAbsolutePath());
                        continue;
                    }
                    String actual = sha256Hex(jar);
                    if (actual == null) {
                        logPluginRejected("PLUGIN_SHA256_FAILED", "Rejected plugin jar (sha256 compute failed): " + jar.getAbsolutePath());
                        continue;
                    }
                    if (!actual.equalsIgnoreCase(expected.trim())) {
                        logPluginRejected("PLUGIN_SHA256_MISMATCH", "Rejected plugin jar (sha256 mismatch): " + jar.getAbsolutePath());
                        continue;
                    }
                }
                urls.add(jar.toURI().toURL());
            }
            if (urls.isEmpty()) {
                return new LoadedProviders(Collections.<CommandProvider>emptyList(), null);
            }

            URLClassLoader loader = null;
            try {
                loader = new URLClassLoader(urls.toArray(new URL[0]), parentClassLoader);
                ServiceLoader<CommandProvider> sl = ServiceLoader.load(CommandProvider.class, loader);
                List<CommandProvider> providers = new ArrayList<>();
                for (CommandProvider p : sl) {
                    if (p != null) {
                        providers.add(p);
                    }
                }
                return new LoadedProviders(providers, loader);
            } catch (ServiceConfigurationError e) {
                SleuthLogger.warn("Plugin ServiceLoader configuration error: " + e.getMessage());
                closeLoaderQuietly(loader);
                return new LoadedProviders(Collections.<CommandProvider>emptyList(), null);
            } catch (RuntimeException e) {
                SleuthLogger.warn("Plugin ServiceLoader failed: " + e.getMessage(), e);
                closeLoaderQuietly(loader);
                return new LoadedProviders(Collections.<CommandProvider>emptyList(), null);
            }
        } catch (IOException e) {
            SleuthLogger.warn("Failed to load plugins from directory: " + e.getMessage(), e);
            return new LoadedProviders(Collections.<CommandProvider>emptyList(), null);
        } catch (RuntimeException e) {
            SleuthLogger.warn("Failed to load plugins from directory: " + e.getMessage(), e);
            return new LoadedProviders(Collections.<CommandProvider>emptyList(), null);
        }
    }

    private static void closeLoaderQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (Exception ignore) {
            // ignore
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
        try {
            auditLogger.logSecurityViolation(null, "plugin-loader", violation, details);
        } catch (RuntimeException e) {
            SleuthLogger.debug("Failed to write plugin rejection audit (ignored): " + e.getMessage(), e);
        }
    }
}
