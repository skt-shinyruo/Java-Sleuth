package com.javasleuth.config;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.schema.ConfigKey;
import com.javasleuth.foundation.config.schema.ConfigValidationResult;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import org.junit.Assert;
import org.junit.Test;

public class ProductionConfigRuntimeValidationTest {
    @Test
    public void rejectsOutOfRangeKnownKey() {
        ProductionConfig config = new ProductionConfig();
        try {
            config.setRuntimeConfig("monitoring.watch.queue.capacity", "0");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("monitoring.watch.queue.capacity"));
        }
    }

    @Test
    public void rejectsInvalidBoolean() {
        ProductionConfig config = new ProductionConfig();
        try {
            config.setRuntimeConfig("monitoring.trace.drop.on.full", "maybe");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("not_boolean"));
        }
    }

    @Test
    public void rejectsUnknownRuntimeKey() {
        ProductionConfig config = new ProductionConfig();
        try {
            config.setRuntimeConfig("unknown.runtime.key", "value");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("Unknown config key"));
        }
    }

    @Test
    public void normalizesBooleanBeforeStorage() {
        ProductionConfig config = new ProductionConfig();
        config.setRuntimeConfig("monitoring.trace.drop.on.full", "FALSE");
        Assert.assertEquals("false", config.getString("monitoring.trace.drop.on.full", "true"));
    }

    @Test
    public void rejectsNonFiniteDoubleRuntimeValue() {
        ConfigKey<Double> key = ConfigKey.doubleKey("test.double")
            .defaultValue(1.0)
            .build();

        ConfigValidationResult nan = key.validateRuntimeValue("NaN");
        Assert.assertFalse(nan.isValid());
        Assert.assertTrue(nan.getError().contains("not_finite"));

        ConfigValidationResult infinity = key.validateRuntimeValue("Infinity");
        Assert.assertFalse(infinity.isValid());
        Assert.assertTrue(infinity.getError().contains("not_finite"));
    }

    @Test
    public void masksSensitiveRawValueInRuntimeValidationError() {
        ConfigKey<String> key = ConfigKey.stringKey("test.secret")
            .sensitive()
            .allowedStrings("ok")
            .defaultValue("")
            .build();

        ConfigValidationResult result = key.validateRuntimeValue("leaked-secret");

        Assert.assertFalse(result.isValid());
        Assert.assertFalse(result.getError().contains("leaked-secret"));
        Assert.assertTrue(result.getError().contains("<sensitive>"));
    }

    @Test
    public void exposesSchemaReadBoundaryConveniences() {
        ProductionConfig config = new ProductionConfig();
        config.setRuntimeConfig("server.port", "4567");

        Integer port = config.read(SleuthConfigSchema.SERVER_PORT);
        String rawPort = config.getKnownRaw(SleuthConfigSchema.SERVER_PORT);
        SleuthConfig typed = config.typedSnapshot();

        Assert.assertEquals(Integer.valueOf(4567), port);
        Assert.assertEquals("4567", rawPort);
        Assert.assertEquals(4567, typed.server().getPort());
    }
}
