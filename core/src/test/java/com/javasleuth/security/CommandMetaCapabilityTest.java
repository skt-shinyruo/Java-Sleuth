package com.javasleuth.security;

import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class CommandMetaCapabilityTest {
    @Test
    public void defaultMetaHasNoCapabilitiesOrBootstrapRequirements() {
        CommandMeta meta = CommandMeta.viewer(true, false);

        Assert.assertFalse(meta.hasCapability(CommandCapability.USES_INSTRUMENTATION));
        Assert.assertFalse(meta.requiresBootstrap());
        Assert.assertTrue(meta.getCapabilities().isEmpty());
        Assert.assertTrue(meta.getRequiredBootstrapClasses().isEmpty());
    }

    @Test
    public void requiresBootstrapRecordsClassAndImpliesInstrumentationCapability() {
        CommandMeta meta = CommandMeta.operator(false, true)
            .requiresBootstrap("com.example.Bridge");

        Assert.assertTrue(meta.requiresBootstrap());
        Assert.assertTrue(meta.hasCapability(CommandCapability.USES_INSTRUMENTATION));
        Assert.assertTrue(meta.getRequiredBootstrapClasses().contains("com.example.Bridge"));
    }

    @Test
    public void existingCopyMethodsPreserveCapabilitiesAndBootstrapRequirements() {
        CommandMeta meta = CommandMeta.operator(false, true)
            .requiresBootstrap("com.example.Bridge")
            .withCapability(CommandCapability.LONG_RUNNING)
            .withRateLimit(3)
            .withImpact(CommandMeta.ImpactLevel.MEDIUM)
            .withAudit(false)
            .withSubcommandRole("set", com.javasleuth.foundation.security.AuthenticationManager.UserRole.ADMIN);

        Assert.assertTrue(meta.hasCapability(CommandCapability.USES_INSTRUMENTATION));
        Assert.assertTrue(meta.hasCapability(CommandCapability.LONG_RUNNING));
        Assert.assertTrue(meta.getRequiredBootstrapClasses().contains("com.example.Bridge"));
        Assert.assertEquals(3, meta.getMaxExecutionsPerMinute());
        Assert.assertEquals(CommandMeta.ImpactLevel.MEDIUM, meta.getImpactLevel());
        Assert.assertFalse(meta.isRequiresAudit());
    }

    @Test
    public void blankBootstrapClassesAreIgnored() {
        CommandMeta meta = CommandMeta.viewer(false, false)
            .requiresBootstrap((String) null)
            .requiresBootstrap("")
            .requiresBootstrap("   ");

        Assert.assertFalse(meta.requiresBootstrap());
        Assert.assertTrue(meta.getRequiredBootstrapClasses().isEmpty());
    }

    @Test
    public void collectionsAreImmutableAndBulkApisDeduplicateValues() {
        CommandMeta meta = CommandMeta.operator(false, false)
            .withCapabilities(Arrays.asList(
                CommandCapability.LONG_RUNNING,
                CommandCapability.LONG_RUNNING,
                null
            ))
            .requiresBootstrap(Arrays.asList(
                "com.example.Bridge",
                "com.example.Bridge",
                "  ",
                null
            ));

        Assert.assertEquals(2, meta.getCapabilities().size());
        Assert.assertEquals(1, meta.getRequiredBootstrapClasses().size());

        try {
            meta.getCapabilities().add(CommandCapability.WRITES_DISK);
            Assert.fail("Expected immutable capabilities");
        } catch (UnsupportedOperationException expected) {
            // ok
        }

        try {
            meta.getRequiredBootstrapClasses().add("x.Y");
            Assert.fail("Expected immutable bootstrap requirements");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
