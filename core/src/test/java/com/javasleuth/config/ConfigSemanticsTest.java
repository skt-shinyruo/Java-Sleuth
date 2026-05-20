package com.javasleuth.foundation.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Test;

public class ConfigSemanticsTest {

    @Test
    public void snapshotPriorityAndOriginShouldBeExplainable() {
        Properties defaults = new Properties();
        defaults.setProperty("k", "default");

        Properties file = new Properties();
        file.setProperty("k", "file");

        Properties base = new Properties();
        base.putAll(defaults);
        base.putAll(file);

        Map<String, String> runtime = new HashMap<>();
        runtime.put("k", "runtime");

        Map<String, String> sys = new HashMap<>();
        sys.put("k", "system");

        ConfigSnapshot snapshot = new ConfigSnapshot(base, defaults, file, runtime, sys);
        Assert.assertEquals("runtime", snapshot.getString("k", "x"));
        Assert.assertEquals(ConfigOrigin.RUNTIME_OVERRIDE, snapshot.getOrigin("k"));

        ConfigSnapshot noRuntime = new ConfigSnapshot(base, defaults, file, new HashMap<>(), sys);
        Assert.assertEquals("system", noRuntime.getString("k", "x"));
        Assert.assertEquals(ConfigOrigin.SYSTEM_PROPERTY, noRuntime.getOrigin("k"));

        ConfigSnapshot noSys = new ConfigSnapshot(base, defaults, file, new HashMap<>(), new HashMap<>());
        Assert.assertEquals("file", noSys.getString("k", "x"));
        Assert.assertEquals(ConfigOrigin.FILE, noSys.getOrigin("k"));

        Properties baseDefaultOnly = new Properties();
        baseDefaultOnly.putAll(defaults);
        ConfigSnapshot defaultOnly = new ConfigSnapshot(baseDefaultOnly, defaults, new Properties(), new HashMap<>(), new HashMap<>());
        Assert.assertEquals("default", defaultOnly.getString("k", "x"));
        Assert.assertEquals(ConfigOrigin.DEFAULT, defaultOnly.getOrigin("k"));
    }

    @Test
    public void runtimeConfigStoreShouldAuditAndMaskSensitiveValues() {
        SensitiveKeyMasker masker = new SensitiveKeyMasker();
        RuntimeConfigStore store = new RuntimeConfigStore(masker, 10);

        store.set("security.auth.admin.password", "abcdef", ConfigUpdateSource.COMMAND);
        List<ConfigChange> changes1 = store.getRecentChanges(10);
        Assert.assertFalse(changes1.isEmpty());
        ConfigChange c1 = changes1.get(changes1.size() - 1);
        Assert.assertEquals("security.auth.admin.password", c1.getKey());
        Assert.assertEquals("null", c1.getOldValueSummary());
        Assert.assertEquals("ab***ef", c1.getNewValueSummary());
        Assert.assertEquals(ConfigUpdateSource.COMMAND, c1.getSource());

        store.set("security.auth.admin.password", "zzz", ConfigUpdateSource.COMMAND);
        ConfigChange c2 = store.getRecentChanges(10).get(store.getRecentChanges(10).size() - 1);
        Assert.assertEquals("ab***ef", c2.getOldValueSummary());
        Assert.assertEquals("****", c2.getNewValueSummary());

        store.remove("security.auth.admin.password", ConfigUpdateSource.COMMAND);
        ConfigChange c3 = store.getRecentChanges(10).get(store.getRecentChanges(10).size() - 1);
        Assert.assertEquals("****", c3.getOldValueSummary());
        Assert.assertEquals("null", c3.getNewValueSummary());
    }

    @Test
    public void persisterShouldOptionallyIncludeRuntimeOverrides() throws Exception {
        File tmp = File.createTempFile("sleuth-config-test", ".properties");
        tmp.deleteOnExit();

        Properties base = new Properties();
        base.setProperty("a", "1");

        Map<String, String> runtime = new HashMap<>();
        runtime.put("b", "2");
        runtime.put("security.auth.admin.password", "abcdef");

        ConfigPersister persister = new ConfigPersister();

        persister.save(tmp, base, runtime, false);
        Properties loaded1 = new Properties();
        try (FileInputStream in = new FileInputStream(tmp)) {
            loaded1.load(in);
        }
        Assert.assertEquals("1", loaded1.getProperty("a"));
        Assert.assertNull(loaded1.getProperty("b"));

        persister.save(tmp, base, runtime, true);
        Properties loaded2 = new Properties();
        try (FileInputStream in = new FileInputStream(tmp)) {
            loaded2.load(in);
        }
        Assert.assertEquals("1", loaded2.getProperty("a"));
        Assert.assertEquals("2", loaded2.getProperty("b"));
        Assert.assertEquals("abcdef", loaded2.getProperty("security.auth.admin.password"));
    }

    @Test
    public void productionConfigShouldResolveFromLoadedLayersAndRuntimeOnly() throws Exception {
        String oldFile = System.getProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
        String oldPort = System.getProperty("sleuth.server.port");
        String oldCustom = System.getProperty("sleuth.demo.custom");

        File tmp = File.createTempFile("sleuth-config-layering", ".properties");
        tmp.deleteOnExit();
        Properties p = new Properties();
        p.setProperty("server.port", "4567");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "test");
        }

        try {
            System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, tmp.getAbsolutePath());
            System.setProperty("sleuth.server.port", "5678");
            System.setProperty("sleuth.demo.custom", "custom-load");

            ProductionConfig config = ProductionConfig.createDefault();
            Assert.assertEquals("5678", config.getString("server.port", "x"));
            Assert.assertEquals(ConfigOrigin.SYSTEM_PROPERTY, config.getOrigin("server.port"));
            Assert.assertEquals("5678", config.snapshot().getString("server.port", "x"));
            Assert.assertEquals(ConfigOrigin.SYSTEM_PROPERTY, config.snapshot().getOrigin("server.port"));
            Assert.assertEquals("custom-load", config.getString("demo.custom", "x"));
            Assert.assertEquals(ConfigOrigin.SYSTEM_PROPERTY, config.getOrigin("demo.custom"));

            System.setProperty("sleuth.server.port", "6789");
            System.setProperty("sleuth.demo.custom", "custom-late");
            Assert.assertEquals("5678", config.getString("server.port", "x"));
            Assert.assertEquals(ConfigOrigin.SYSTEM_PROPERTY, config.getOrigin("server.port"));
            Assert.assertEquals("5678", config.snapshot().getString("server.port", "x"));
            Assert.assertEquals(ConfigOrigin.SYSTEM_PROPERTY, config.snapshot().getOrigin("server.port"));
            Assert.assertEquals("custom-load", config.getString("demo.custom", "x"));

            config.setRuntimeConfig("server.port", "7890", ConfigUpdateSource.COMMAND);
            Assert.assertEquals("7890", config.getString("server.port", "x"));
            Assert.assertEquals(ConfigOrigin.RUNTIME_OVERRIDE, config.getOrigin("server.port"));
            Assert.assertEquals("7890", config.snapshot().getString("server.port", "x"));
            Assert.assertEquals(ConfigOrigin.RUNTIME_OVERRIDE, config.snapshot().getOrigin("server.port"));

            config.removeRuntimeConfig("server.port", ConfigUpdateSource.COMMAND);
            Assert.assertEquals("5678", config.getString("server.port", "x"));
            Assert.assertEquals(ConfigOrigin.SYSTEM_PROPERTY, config.getOrigin("server.port"));

            System.clearProperty("sleuth.server.port");
            System.clearProperty("sleuth.demo.custom");
            Assert.assertEquals("5678", config.getString("server.port", "x"));
            Assert.assertEquals(ConfigOrigin.SYSTEM_PROPERTY, config.getOrigin("server.port"));
            Assert.assertEquals("custom-load", config.getString("demo.custom", "x"));
            Assert.assertEquals(ConfigOrigin.SYSTEM_PROPERTY, config.getOrigin("demo.custom"));
        } finally {
            if (oldFile == null) {
                System.clearProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
            } else {
                System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, oldFile);
            }
            if (oldPort == null) {
                System.clearProperty("sleuth.server.port");
            } else {
                System.setProperty("sleuth.server.port", oldPort);
            }
            if (oldCustom == null) {
                System.clearProperty("sleuth.demo.custom");
            } else {
                System.setProperty("sleuth.demo.custom", oldCustom);
            }
            try {
                tmp.delete();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    public void explicitSnapshotShouldNotCaptureGlobalSystemPropertiesImplicitly() {
        String old = System.getProperty("sleuth.snapshot.only");
        try {
            Properties defaults = new Properties();
            defaults.setProperty("snapshot.only", "default");

            Properties base = new Properties();
            base.putAll(defaults);

            System.setProperty("sleuth.snapshot.only", "system");
            ConfigSnapshot snapshot = new ConfigSnapshot(base, defaults, new Properties(), new HashMap<>(), null);

            Assert.assertEquals("default", snapshot.getString("snapshot.only", "x"));
            Assert.assertEquals(ConfigOrigin.DEFAULT, snapshot.getOrigin("snapshot.only"));
        } finally {
            if (old == null) {
                System.clearProperty("sleuth.snapshot.only");
            } else {
                System.setProperty("sleuth.snapshot.only", old);
            }
        }
    }

    @Test
    public void loaderShouldSupportStrictForbiddenKeysPolicy() throws Exception {
        String oldFile = System.getProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
        String oldPolicy = System.getProperty("sleuth.config.forbidden.keys.policy");

        File tmp = File.createTempFile("sleuth-config-forbidden", ".properties");
        tmp.deleteOnExit();
        Properties p = new Properties();
        p.setProperty("protocol.text.end.marker.enabled", "true");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            p.store(out, "test");
        }

        try {
            System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, tmp.getAbsolutePath());

            System.setProperty("sleuth.config.forbidden.keys.policy", "strict");
            try {
                new ConfigLoader().load();
                Assert.fail("Expected strict policy to reject forbidden keys");
            } catch (IllegalArgumentException expected) {
                Assert.assertTrue(expected.getMessage().contains("protocol.text.end.marker.enabled"));
            }

            System.setProperty("sleuth.config.forbidden.keys.policy", "warn");
            new ConfigLoader().load(); // should not throw
        } finally {
            if (oldFile == null) {
                System.clearProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
            } else {
                System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, oldFile);
            }
            if (oldPolicy == null) {
                System.clearProperty("sleuth.config.forbidden.keys.policy");
            } else {
                System.setProperty("sleuth.config.forbidden.keys.policy", oldPolicy);
            }
            // Best-effort cleanup.
            try {
                tmp.delete();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    public void loaderShouldRejectSecurityModeKeyWhenStrict() throws Exception {
        String oldFile = System.getProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
        String oldPolicy = System.getProperty("sleuth.config.forbidden.keys.policy");
        String oldMode = System.getProperty("sleuth.security.mode");

        File tmp = File.createTempFile("sleuth-config-forbidden-security-mode", ".properties");
        tmp.deleteOnExit();
        Properties p = new Properties();
        p.setProperty("security.mode", "hmac");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            p.store(out, "test");
        }

        try {
            System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, tmp.getAbsolutePath());
            System.setProperty("sleuth.config.forbidden.keys.policy", "strict");
            System.clearProperty("sleuth.security.mode");

            try {
                new ConfigLoader().load();
                Assert.fail("Expected strict policy to reject forbidden key: security.mode");
            } catch (IllegalArgumentException expected) {
                Assert.assertTrue(expected.getMessage().contains("security.mode"));
            }
        } finally {
            if (oldFile == null) {
                System.clearProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
            } else {
                System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, oldFile);
            }
            if (oldPolicy == null) {
                System.clearProperty("sleuth.config.forbidden.keys.policy");
            } else {
                System.setProperty("sleuth.config.forbidden.keys.policy", oldPolicy);
            }
            if (oldMode == null) {
                System.clearProperty("sleuth.security.mode");
            } else {
                System.setProperty("sleuth.security.mode", oldMode);
            }
            // Best-effort cleanup.
            try {
                tmp.delete();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    public void loaderShouldRejectSecurityHmacSecretKeyWhenStrict() throws Exception {
        String oldFile = System.getProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
        String oldPolicy = System.getProperty("sleuth.config.forbidden.keys.policy");
        String oldSecret = System.getProperty("sleuth.security.hmac.secret");

        File tmp = File.createTempFile("sleuth-config-forbidden-security-hmac-secret", ".properties");
        tmp.deleteOnExit();
        Properties p = new Properties();
        p.setProperty("security.hmac.secret", "test-secret");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            p.store(out, "test");
        }

        try {
            System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, tmp.getAbsolutePath());
            System.setProperty("sleuth.config.forbidden.keys.policy", "strict");
            System.clearProperty("sleuth.security.hmac.secret");

            try {
                new ConfigLoader().load();
                Assert.fail("Expected strict policy to reject forbidden key: security.hmac.secret");
            } catch (IllegalArgumentException expected) {
                Assert.assertTrue(expected.getMessage().contains("security.hmac.secret"));
            }
        } finally {
            if (oldFile == null) {
                System.clearProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
            } else {
                System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, oldFile);
            }
            if (oldPolicy == null) {
                System.clearProperty("sleuth.config.forbidden.keys.policy");
            } else {
                System.setProperty("sleuth.config.forbidden.keys.policy", oldPolicy);
            }
            if (oldSecret == null) {
                System.clearProperty("sleuth.security.hmac.secret");
            } else {
                System.setProperty("sleuth.security.hmac.secret", oldSecret);
            }
            // Best-effort cleanup.
            try {
                tmp.delete();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    public void loaderShouldRejectForbiddenSystemPropertyWhenStrict() {
        String oldPolicy = System.getProperty("sleuth.config.forbidden.keys.policy");
        String oldProtocol = System.getProperty("sleuth.protocol.mode");

        try {
            System.setProperty("sleuth.config.forbidden.keys.policy", "strict");
            System.setProperty("sleuth.protocol.mode", "framed");

            try {
                new ConfigLoader().load();
                Assert.fail("Expected strict policy to reject forbidden system property: protocol.mode");
            } catch (IllegalArgumentException expected) {
                Assert.assertTrue(expected.getMessage().contains("protocol.mode"));
            }
        } finally {
            if (oldPolicy == null) {
                System.clearProperty("sleuth.config.forbidden.keys.policy");
            } else {
                System.setProperty("sleuth.config.forbidden.keys.policy", oldPolicy);
            }
            if (oldProtocol == null) {
                System.clearProperty("sleuth.protocol.mode");
            } else {
                System.setProperty("sleuth.protocol.mode", oldProtocol);
            }
        }
    }
}
