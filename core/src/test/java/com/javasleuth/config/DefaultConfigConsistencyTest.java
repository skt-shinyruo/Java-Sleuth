package com.javasleuth.config;

import java.io.InputStream;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Test;

public class DefaultConfigConsistencyTest {

    @Test
    public void sleuthDefaultPropertiesShouldMatchSleuthDefaultsFallback() throws Exception {
        Properties fromFile = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/sleuth-default.properties")) {
            Assert.assertNotNull("Missing /sleuth-default.properties on classpath", in);
            fromFile.load(in);
        }

        Properties fallback = new Properties();
        SleuthDefaults.apply(fallback);

        for (String key : fallback.stringPropertyNames()) {
            Assert.assertTrue("Missing key in sleuth-default.properties: " + key, fromFile.getProperty(key) != null);
            Assert.assertEquals("Default mismatch for key: " + key, fallback.getProperty(key), fromFile.getProperty(key));
        }
    }
}

