package com.javasleuth.foundation.config;

import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import java.util.HashMap;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Test;

public class SleuthConfigParserTest {

    @Test
    public void shouldDeriveTextMaxLineBytesWhenOnlyFramePayloadIsOverridden() {
        Properties defaults = new Properties();
        defaults.setProperty("server.bind.address", "127.0.0.1");
        defaults.setProperty("server.port", "3658");
        defaults.setProperty("server.max.connections", "10");
        defaults.setProperty("server.executor.queue.capacity", "50");
        defaults.setProperty("server.connection.timeout", "30000");
        defaults.setProperty("server.socket.timeout", "1000");

        defaults.setProperty("protocol.streaming.enabled", "true");
        defaults.setProperty("protocol.frame.max.payload", "4096");
        defaults.setProperty("protocol.text.max.line.bytes", "8192");

        defaults.setProperty("security.mode", "off");
        defaults.setProperty("security.authorization.enabled", "false");
        defaults.setProperty("security.anonymous.viewer", "true");
        defaults.setProperty("security.hmac.session.role", "operator");

        Properties file = new Properties();
        file.setProperty("protocol.frame.max.payload", "9000");

        Properties base = new Properties();
        base.putAll(defaults);
        base.putAll(file);

        ConfigSnapshot snapshot = new ConfigSnapshot(base, defaults, file, new HashMap<>(), new HashMap<>());
        SleuthConfig typed = SleuthConfigParser.parse(snapshot);

        Assert.assertEquals(9000, typed.protocol().getFrameMaxPayloadBytes());
        // derivedLineMax = max(8192, frame*2) = 18000
        Assert.assertEquals(18000, typed.protocol().getTextMaxLineBytes());
    }

    @Test
    public void shouldRespectExplicitTextMaxLineBytesOverride() {
        Properties defaults = new Properties();
        defaults.setProperty("server.bind.address", "127.0.0.1");
        defaults.setProperty("server.port", "3658");
        defaults.setProperty("server.max.connections", "10");
        defaults.setProperty("server.executor.queue.capacity", "50");
        defaults.setProperty("server.connection.timeout", "30000");
        defaults.setProperty("server.socket.timeout", "1000");

        defaults.setProperty("protocol.streaming.enabled", "true");
        defaults.setProperty("protocol.frame.max.payload", "4096");
        defaults.setProperty("protocol.text.max.line.bytes", "8192");

        defaults.setProperty("security.mode", "off");
        defaults.setProperty("security.authorization.enabled", "false");
        defaults.setProperty("security.anonymous.viewer", "true");
        defaults.setProperty("security.hmac.session.role", "operator");

        Properties file = new Properties();
        file.setProperty("protocol.frame.max.payload", "9000");
        file.setProperty("protocol.text.max.line.bytes", "10000");

        Properties base = new Properties();
        base.putAll(defaults);
        base.putAll(file);

        ConfigSnapshot snapshot = new ConfigSnapshot(base, defaults, file, new HashMap<>(), new HashMap<>());
        SleuthConfig typed = SleuthConfigParser.parse(snapshot);

        Assert.assertEquals(9000, typed.protocol().getFrameMaxPayloadBytes());
        Assert.assertEquals(10000, typed.protocol().getTextMaxLineBytes());
    }

    @Test
    public void shouldFailFastOnInvalidSecurityModeWhenExplicitlyConfigured() {
        Properties defaults = new Properties();
        defaults.setProperty("server.bind.address", "127.0.0.1");
        defaults.setProperty("server.port", "3658");
        defaults.setProperty("server.max.connections", "10");
        defaults.setProperty("server.executor.queue.capacity", "50");
        defaults.setProperty("server.connection.timeout", "30000");
        defaults.setProperty("server.socket.timeout", "1000");

        defaults.setProperty("protocol.streaming.enabled", "true");
        defaults.setProperty("protocol.frame.max.payload", "4096");
        defaults.setProperty("protocol.text.max.line.bytes", "8192");

        defaults.setProperty("security.mode", "off");
        defaults.setProperty("security.authorization.enabled", "false");
        defaults.setProperty("security.anonymous.viewer", "true");
        defaults.setProperty("security.hmac.session.role", "operator");

        Properties file = new Properties();
        file.setProperty("security.mode", "tls");

        Properties base = new Properties();
        base.putAll(defaults);
        base.putAll(file);

        ConfigSnapshot snapshot = new ConfigSnapshot(base, defaults, file, new HashMap<>(), new HashMap<>());
        try {
            SleuthConfigParser.parse(snapshot);
            Assert.fail("Expected invalid security.mode to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("security.mode"));
        }
    }
}
