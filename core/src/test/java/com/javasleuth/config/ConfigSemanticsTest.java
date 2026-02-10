package com.javasleuth.config;

import java.io.File;
import java.io.FileInputStream;
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

        store.set("security.hmac.secret", "abcdef", ConfigUpdateSource.COMMAND);
        List<ConfigChange> changes1 = store.getRecentChanges(10);
        Assert.assertFalse(changes1.isEmpty());
        ConfigChange c1 = changes1.get(changes1.size() - 1);
        Assert.assertEquals("security.hmac.secret", c1.getKey());
        Assert.assertEquals("null", c1.getOldValueSummary());
        Assert.assertEquals("ab***ef", c1.getNewValueSummary());
        Assert.assertEquals(ConfigUpdateSource.COMMAND, c1.getSource());

        store.set("security.hmac.secret", "zzz", ConfigUpdateSource.COMMAND);
        ConfigChange c2 = store.getRecentChanges(10).get(store.getRecentChanges(10).size() - 1);
        Assert.assertEquals("ab***ef", c2.getOldValueSummary());
        Assert.assertEquals("****", c2.getNewValueSummary());

        store.remove("security.hmac.secret", ConfigUpdateSource.COMMAND);
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
        runtime.put("security.hmac.secret", "abcdef");

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
        Assert.assertEquals("abcdef", loaded2.getProperty("security.hmac.secret"));
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
}

