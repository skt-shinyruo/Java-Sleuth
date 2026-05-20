package com.javasleuth.command;

import com.javasleuth.core.command.CommandProvider;
import com.javasleuth.core.command.CommandProviderContext;
import com.javasleuth.core.command.CommandRegistry;
import com.javasleuth.core.command.plugin.CommandProviderLoader;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class CommandProviderLoaderSecurityTest {
    @Test
    public void pluginDirectory_requiresAllowlistUnlessUnsafeAllJarsIsEnabled() throws Exception {
        Assume.assumeNotNull(ToolProvider.getSystemJavaCompiler());

        File pluginDir = createTempDir("sleuth-plugin-dir-");
        writeRestrictedPluginJar(new File(pluginDir, "restricted.jar"), "external.DefaultDeniedPlugin", "denied");

        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("plugins.enabled", "true");
        config.setRuntimeConfig("plugins.directory", pluginDir.getAbsolutePath());

        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProviderLoader.LoadedProviders loaded = new CommandProviderLoader(
                config,
                auditLogger,
                CommandProviderLoaderSecurityTest.class.getClassLoader()
            ).load(null);

            Assert.assertTrue(loaded.getProviders().isEmpty());
            Assert.assertNull(loaded.getPluginClassLoader());
        }
    }

    @Test
    public void pluginDirectory_loadsAllowlistedRestrictedSpiProvider() throws Exception {
        Assume.assumeNotNull(ToolProvider.getSystemJavaCompiler());

        File pluginDir = createTempDir("sleuth-plugin-dir-");
        File jar = new File(pluginDir, "restricted.jar");
        writeRestrictedPluginJar(jar, "external.AllowlistedPlugin", "allowlisted");

        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("plugins.enabled", "true");
        config.setRuntimeConfig("plugins.directory", pluginDir.getAbsolutePath());
        config.setRuntimeConfig("plugins.allowlist.sha256", jar.getName() + ":" + sha256Hex(jar));

        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProviderLoader.LoadedProviders loaded = new CommandProviderLoader(
                config,
                auditLogger,
                CommandProviderLoaderSecurityTest.class.getClassLoader()
            ).load(null);
            try {
                Assert.assertEquals(1, loaded.getProviders().size());
                CommandRegistry registry = new CommandRegistry(
                    config,
                    null,
                    auditLogger,
                    loaded.getProviders(),
                    loaded.getPluginClassLoader(),
                    new CommandProviderContext(null, null, null, config, auditLogger, null, null, null, null, null, null, null)
                );
                try {
                    Assert.assertNotNull(registry.getEntry("allowlisted:allowlisted"));
                    Assert.assertEquals("allowlisted", registry.getEntry("allowlisted:allowlisted").getCommand().execute(new String[]{"allowlisted"}));
                } finally {
                    registry.shutdown();
                }
            } finally {
                closeQuietly(loaded.getPluginClassLoader());
            }
        }
    }

    @Test
    public void legacyDirectoryProvider_requiresUnsafeBridge() throws Exception {
        Assume.assumeNotNull(ToolProvider.getSystemJavaCompiler());

        File pluginDir = createTempDir("sleuth-plugin-dir-");
        File jar = new File(pluginDir, "legacy.jar");
        writeLegacyPluginJar(jar, "external.LegacyPlugin", "legacy");

        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("plugins.enabled", "true");
        config.setRuntimeConfig("plugins.directory", pluginDir.getAbsolutePath());
        config.setRuntimeConfig("plugins.allowlist.sha256", jar.getName() + ":" + sha256Hex(jar));

        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProviderLoader.LoadedProviders loaded = new CommandProviderLoader(
                config,
                auditLogger,
                CommandProviderLoaderSecurityTest.class.getClassLoader()
            ).load(null);
            try {
                Assert.assertTrue(loaded.getProviders().isEmpty());
            } finally {
                closeQuietly(loaded.getPluginClassLoader());
            }

            config.setRuntimeConfig("plugins.unsafe.legacy-provider-bridge.enabled", "true");
            loaded = new CommandProviderLoader(
                config,
                auditLogger,
                CommandProviderLoaderSecurityTest.class.getClassLoader()
            ).load(null);
            try {
                Assert.assertEquals(1, loaded.getProviders().size());
                Assert.assertFalse(loaded.getProviders().get(0).getInfo().isBuiltin());
            } finally {
                closeQuietly(loaded.getPluginClassLoader());
            }
        }
    }

    @Test
    public void pluginServiceConfigurationFailure_doesNotDropBuiltinProvider() throws Exception {
        Assume.assumeNotNull(ToolProvider.getSystemJavaCompiler());

        File pluginDir = createTempDir("sleuth-plugin-dir-");
        File jar = new File(pluginDir, "broken.jar");
        writeBrokenServiceJar(jar);

        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("plugins.enabled", "true");
        config.setRuntimeConfig("plugins.directory", pluginDir.getAbsolutePath());
        config.setRuntimeConfig("plugins.allowlist.sha256", jar.getName() + ":" + sha256Hex(jar));

        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProvider builtin = new CommandProvider() {
                @Override
                public String getName() {
                    return "builtin";
                }
            };

            CommandProviderLoader.LoadedProviders loaded = new CommandProviderLoader(
                config,
                auditLogger,
                CommandProviderLoaderSecurityTest.class.getClassLoader()
            ).load(builtin);
            try {
                Assert.assertEquals(1, loaded.getProviders().size());
                Assert.assertSame(builtin, loaded.getProviders().get(0));
                Assert.assertNull(loaded.getPluginClassLoader());
            } finally {
                closeQuietly(loaded.getPluginClassLoader());
            }
        }
    }

    private static void writeRestrictedPluginJar(File jar, String binaryName, String commandName) throws Exception {
        String src =
            "package " + packageName(binaryName) + ";\n"
                + "import com.javasleuth.core.command.Command;\n"
                + "import com.javasleuth.core.command.CommandDescriptor;\n"
                + "import com.javasleuth.core.command.spi.RestrictedCommandProvider;\n"
                + "import com.javasleuth.core.command.spi.RestrictedCommandProviderContext;\n"
                + "import com.javasleuth.foundation.security.CommandMeta;\n"
                + "import java.util.Collection;\n"
                + "import java.util.Collections;\n"
                + "public final class " + simpleName(binaryName) + " implements RestrictedCommandProvider {\n"
                + "  public String getName() { return \"" + commandName + "\"; }\n"
                + "  public String getNamespace() { return \"" + commandName + "\"; }\n"
                + "  public Collection<CommandDescriptor> getCommandDescriptors(RestrictedCommandProviderContext context) {\n"
                + "    return Collections.singletonList(CommandDescriptor.of(\"" + commandName + "\", new Command() {\n"
                + "      public String execute(String[] args) { return \"" + commandName + "\"; }\n"
                + "      public String getDescription() { return \"" + commandName + "\"; }\n"
                + "    }, CommandMeta.viewer(true, false)));\n"
                + "  }\n"
                + "}\n";
        writeProviderJar(
            jar,
            binaryName,
            src,
            "com.javasleuth.core.command.spi.RestrictedCommandProvider",
            binaryName
        );
    }

    private static void writeLegacyPluginJar(File jar, String binaryName, String commandName) throws Exception {
        String src =
            "package " + packageName(binaryName) + ";\n"
                + "import com.javasleuth.core.command.Command;\n"
                + "import com.javasleuth.core.command.CommandProvider;\n"
                + "import java.util.Collections;\n"
                + "import java.util.Map;\n"
                + "public final class " + simpleName(binaryName) + " implements CommandProvider {\n"
                + "  public String getName() { return \"" + commandName + "\"; }\n"
                + "  public Map<String, Command> getCommands() {\n"
                + "    return Collections.<String, Command>singletonMap(\"" + commandName + "\", new Command() {\n"
                + "      public String execute(String[] args) { return \"" + commandName + "\"; }\n"
                + "      public String getDescription() { return \"" + commandName + "\"; }\n"
                + "    });\n"
                + "  }\n"
                + "}\n";
        writeProviderJar(jar, binaryName, src, "com.javasleuth.core.command.CommandProvider", binaryName);
    }

    private static void writeBrokenServiceJar(File jar) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar), mf)) {
            jos.putNextEntry(new JarEntry("META-INF/services/com.javasleuth.core.command.spi.RestrictedCommandProvider"));
            jos.write("external.DoesNotExist\n".getBytes("UTF-8"));
            jos.closeEntry();
        }
    }

    private static void writeProviderJar(
        File jar,
        String binaryName,
        String source,
        String serviceName,
        String providerName
    ) throws Exception {
        File tmp = createTempDir("sleuth-plugin-compile-");
        File srcRoot = new File(tmp, "src");
        File outRoot = new File(tmp, "classes");
        Assert.assertTrue(srcRoot.mkdirs());
        Assert.assertTrue(outRoot.mkdirs());

        File sourceFile = new File(srcRoot, binaryName.replace('.', '/') + ".java");
        File parent = sourceFile.getParentFile();
        Assert.assertTrue(parent.mkdirs());
        try (FileWriter writer = new FileWriter(sourceFile)) {
            writer.write(source);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
        List<String> options = Arrays.asList(
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            outRoot.getAbsolutePath()
        );
        Boolean ok;
        try {
            ok = compiler.getTask(null, fileManager, diagnostics, options, null, fileManager.getJavaFileObjects(sourceFile)).call();
        } finally {
            fileManager.close();
        }
        if (!Boolean.TRUE.equals(ok)) {
            StringBuilder msg = new StringBuilder("Failed to compile plugin test source:\n");
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                msg.append(diagnostic.toString()).append('\n');
            }
            Assert.fail(msg.toString());
        }

        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar), mf)) {
            addClassEntries(jos, outRoot, outRoot);
            jos.putNextEntry(new JarEntry("META-INF/services/" + serviceName));
            jos.write((providerName + "\n").getBytes("UTF-8"));
            jos.closeEntry();
        }
    }

    private static void addClassEntries(JarOutputStream jos, File root, File file) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                addClassEntries(jos, root, child);
            }
            return;
        }
        if (!file.getName().endsWith(".class")) {
            return;
        }
        String entryName = root.toURI().relativize(file.toURI()).getPath();
        jos.putNextEntry(new JarEntry(entryName));
        java.nio.file.Files.copy(file.toPath(), jos);
        jos.closeEntry();
    }

    private static File createTempDir(String prefix) throws Exception {
        File file = File.createTempFile(prefix, "");
        Assert.assertTrue(file.delete());
        Assert.assertTrue(file.mkdirs());
        file.deleteOnExit();
        return file;
    }

    private static String sha256Hex(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        try (InputStream in = java.nio.file.Files.newInputStream(file.toPath())) {
            int read;
            while ((read = in.read(buf)) > 0) {
                md.update(buf, 0, read);
            }
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
    }

    private static String packageName(String binaryName) {
        int idx = binaryName.lastIndexOf('.');
        return idx > 0 ? binaryName.substring(0, idx) : "";
    }

    private static String simpleName(String binaryName) {
        int idx = binaryName.lastIndexOf('.');
        return idx > 0 ? binaryName.substring(idx + 1) : binaryName;
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (Exception ignore) {
            // ignore
        }
    }
}
