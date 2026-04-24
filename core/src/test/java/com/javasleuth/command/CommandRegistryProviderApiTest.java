package com.javasleuth.core.command;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;

public class CommandRegistryProviderApiTest {

    @Test
    public void legacyMapBasedProvider_stillRegistersCommands() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProvider provider = new CommandProvider() {
                @Override
                public String getName() {
                    return "legacy";
                }

                @Override
                public Map<String, Command> getCommands() {
                    Map<String, Command> commands = new LinkedHashMap<>();
                    commands.put("legacy", fixedCommand("legacy-output"));
                    return commands;
                }

                @Override
                public Map<String, CommandMeta> getCommandMeta() {
                    return Collections.singletonMap("legacy", CommandMeta.viewer(true, false));
                }
            };

            CommandRegistry registry = new CommandRegistry(
                config,
                null,
                auditLogger,
                Collections.singletonList(provider),
                null,
                minimalContext(config, auditLogger)
            );

            Assert.assertNotNull(registry.getEntry("legacy"));
            Assert.assertNotNull(registry.getEntry("help"));
            Assert.assertEquals("legacy-output", registry.getEntry("legacy").getCommand().execute(new String[]{"legacy"}));
        }
    }

    @Test
    public void descriptorProvider_receivesAttachScopeContext() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            final CommandProviderContext context = minimalContext(config, auditLogger);
            final AtomicReference<CommandProviderContext> seen = new AtomicReference<>();

            CommandProvider provider = new CommandProvider() {
                @Override
                public String getName() {
                    return "descriptor";
                }

                @Override
                public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext providerContext) {
                    seen.set(providerContext);
                    return Collections.singletonList(
                        CommandDescriptor.of("descriptor", fixedCommand("descriptor-output"), CommandMeta.viewer(true, false))
                    );
                }
            };

            CommandRegistry registry = new CommandRegistry(
                config,
                null,
                auditLogger,
                Collections.singletonList(provider),
                null,
                context
            );

            Assert.assertSame(context, seen.get());
            Assert.assertNotNull(registry.getEntry("descriptor"));
        }
    }

    @Test
    public void builtinDescriptorWithoutMeta_isRejected() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProvider provider = new CommandProvider() {
                @Override
                public String getName() {
                    return "builtin";
                }

                @Override
                public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext providerContext) {
                    return Collections.singletonList(CommandDescriptor.of("unsafe", fixedCommand("unsafe"), null));
                }
            };

            try {
                new CommandRegistry(
                    config,
                    null,
                    auditLogger,
                    Collections.singletonList(provider),
                    null,
                    minimalContext(config, auditLogger)
                );
                Assert.fail("Expected builtin command without metadata to be rejected");
            } catch (IllegalStateException expected) {
                Assert.assertTrue(expected.getMessage().contains("unsafe"));
            }
        }
    }

    @Test
    public void providerInfo_registersCanonicalNamespacedCommands_andExposesProviderMetadata() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProvider provider = new CommandProvider() {
                @Override
                public String getName() {
                    return "example-plugin";
                }

                @Override
                public CommandProviderInfo getInfo() {
                    return CommandProviderInfo.plugin(
                        "example-plugin",
                        "example",
                        "1.0",
                        Arrays.asList("inspect", "diagnostics"),
                        true
                    );
                }

                @Override
                public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext providerContext) {
                    return Collections.singletonList(
                        CommandDescriptor.of("inspect", fixedCommand("inspect-output"), CommandMeta.viewer(true, false))
                    );
                }
            };

            CommandRegistry registry = new CommandRegistry(
                config,
                null,
                auditLogger,
                Collections.singletonList(provider),
                null,
                minimalContext(config, auditLogger)
            );

            Assert.assertNotNull(registry.getEntry("example:inspect"));
            Assert.assertNotNull(registry.getEntry("inspect"));
            Assert.assertTrue(registry.getCommandMap().containsKey("example:inspect"));
            Assert.assertFalse(registry.getCommandMap().containsKey("inspect"));
            Assert.assertEquals("example:inspect", registry.getEntry("inspect").getCanonicalName());

            CommandProviderInfo info = registry.listProviderInfos().iterator().next();
            Assert.assertEquals("example", info.getNamespace());
            Assert.assertEquals("1.0", info.getApiVersion());
            Assert.assertTrue(info.getCapabilities().contains("inspect"));
        }
    }

    @Test
    public void providerWithUnsupportedApiVersion_isRejected() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProvider provider = new CommandProvider() {
                @Override
                public String getName() {
                    return "future-plugin";
                }

                @Override
                public CommandProviderInfo getInfo() {
                    return CommandProviderInfo.plugin(
                        "future-plugin",
                        "future",
                        "99.0",
                        Collections.singletonList("experimental"),
                        true
                    );
                }

                @Override
                public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext providerContext) {
                    return Collections.singletonList(
                        CommandDescriptor.of("future", fixedCommand("future-output"), CommandMeta.viewer(true, false))
                    );
                }
            };

            CommandRegistry registry = new CommandRegistry(
                config,
                null,
                auditLogger,
                Collections.singletonList(provider),
                null,
                minimalContext(config, auditLogger)
            );

            Assert.assertNull(registry.getEntry("future"));
            Assert.assertNull(registry.getEntry("future:future"));
            Assert.assertTrue(registry.listProviderInfos().isEmpty());
        }
    }

    private static CommandProviderContext minimalContext(ProductionConfig config, AuditLogger auditLogger) {
        return new CommandProviderContext(
            null,
            null,
            null,
            config,
            auditLogger,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private static Command fixedCommand(final String output) {
        return new Command() {
            @Override
            public String execute(String[] args) {
                return output;
            }

            @Override
            public String getDescription() {
                return output;
            }
        };
    }
}
