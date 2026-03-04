package com.javasleuth.foundation.config;

import com.javasleuth.foundation.config.ConfigLoader;
import com.javasleuth.foundation.config.ConfigUpdateSource;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.test.SleuthTestState;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Properties;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class ProductionConfigReloadTest {
    private final String oldConfigFile = System.getProperty(ConfigLoader.CONFIG_FILE_PROPERTY);

    @After
    public void cleanup() {
        if (oldConfigFile == null) {
            System.clearProperty(ConfigLoader.CONFIG_FILE_PROPERTY);
        } else {
            System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, oldConfigFile);
        }
        SleuthTestState.resetAll("ProductionConfigReloadTest");
    }

    @Test
    public void reloadShouldPickUpFileChangesAndPreserveRuntimeOverrides() throws Exception {
        File tmp = File.createTempFile("sleuth-config-reload", ".properties");
        tmp.deleteOnExit();

        writeProperties(tmp, "demo.key", "v1");
        System.setProperty(ConfigLoader.CONFIG_FILE_PROPERTY, tmp.getAbsolutePath());

        // Create config after we set configFile sysprop.
        ProductionConfig config = ProductionConfig.createDefault();
        Assert.assertEquals("v1", config.getString("demo.key", "x"));

        config.setRuntimeConfig("demo.runtime", "r1", ConfigUpdateSource.COMMAND);
        Assert.assertEquals("r1", config.getString("demo.runtime", "x"));

        writeProperties(tmp, "demo.key", "v2");
        config.reloadConfiguration();

        Assert.assertEquals("v2", config.getString("demo.key", "x"));
        Assert.assertEquals("r1", config.getString("demo.runtime", "x"));
    }

    private static void writeProperties(File file, String key, String value) throws Exception {
        Properties p = new Properties();
        p.setProperty(key, value);
        try (FileOutputStream out = new FileOutputStream(file)) {
            p.store(out, "test");
        }
    }
}
