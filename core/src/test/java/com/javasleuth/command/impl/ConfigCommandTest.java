package com.javasleuth.core.command.impl;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.test.SleuthTestState;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class ConfigCommandTest {
    @After
    public void cleanup() {
        SleuthTestState.resetAll("ConfigCommandTest");
    }

    @Test
    public void getReadsKnownKeyThroughSchema() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        ConfigCommand command = new ConfigCommand(config);

        command.execute(new String[]{"config", "set", "server.port", "4567"});
        String output = command.execute(new String[]{"config", "get", "server.port"});

        Assert.assertTrue(output, output.contains("server.port = 4567"));
        Assert.assertTrue(output, output.contains("origin=RUNTIME_OVERRIDE"));
    }

    @Test
    public void getRejectsUnknownKeys() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        ConfigCommand command = new ConfigCommand(config);

        String output = command.execute(new String[]{"config", "get", "unknown.config.key"});

        Assert.assertEquals("Unknown config key: unknown.config.key", output);
    }

    @Test
    public void getMasksSensitiveKnownKeys() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        ConfigCommand command = new ConfigCommand(config);

        command.execute(new String[]{"config", "set", "security.auth.admin.password", "supersecret"});
        String output = command.execute(new String[]{"config", "get", "security.auth.admin.password"});

        Assert.assertFalse(output, output.contains("supersecret"));
        Assert.assertTrue(output, output.contains("security.auth.admin.password = su***et"));
    }
}
