package com.javasleuth.foundation.config;

import com.javasleuth.foundation.config.schema.ConfigKey;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
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

        Properties schema = new Properties();
        for (ConfigKey<?> key : SleuthConfigSchema.keys()) {
            String k = key.getKey();
            String v = key.getLiteralDefaultValue();
            schema.setProperty(k, v == null ? "" : v);
        }

        for (String key : schema.stringPropertyNames()) {
            Assert.assertTrue("Missing key in sleuth-default.properties: " + key, fromFile.getProperty(key) != null);
            Assert.assertEquals("Default mismatch for key: " + key, schema.getProperty(key), fromFile.getProperty(key));
            Assert.assertEquals("Fallback mismatch for key: " + key, schema.getProperty(key), fallback.getProperty(key));
        }

        for (String key : fromFile.stringPropertyNames()) {
            Assert.assertTrue("Unknown key in sleuth-default.properties (not in schema): " + key,
                schema.getProperty(key) != null);
        }
    }
}
