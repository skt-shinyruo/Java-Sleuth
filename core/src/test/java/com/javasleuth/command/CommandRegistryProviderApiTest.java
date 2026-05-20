package com.javasleuth.core.command;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.CommandMeta;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
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
    public void externalDescriptorProvider_receivesRestrictedContext() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            final CommandProviderContext context = privilegedContext(config, auditLogger);
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

            Assert.assertNotSame(context, seen.get());
            Assert.assertSame(config, seen.get().getConfig());
            Assert.assertSame(auditLogger, seen.get().getAuditLogger());
            Assert.assertNull(seen.get().getInstrumentation());
            Assert.assertNull(seen.get().getTransformer());
            Assert.assertNull(seen.get().getSpyDispatcher());
            Assert.assertNull(seen.get().getAuthenticationManager());
            Assert.assertNull(seen.get().getDangerousConfirm());
            Assert.assertNull(seen.get().getJobManager());
            Assert.assertNull(seen.get().getVmToolSessionRegistry());
            Assert.assertNull(seen.get().getEnhancementSessionRegistry());
            Assert.assertNotNull(registry.getEntry("descriptor"));
        }
    }

    @Test
    public void builtinDescriptorProvider_receivesAttachScopeContext() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            final CommandProviderContext context = privilegedContext(config, auditLogger);
            final AtomicReference<CommandProviderContext> seen = new AtomicReference<>();

            CommandProvider provider = new TestBuiltinProvider() {
                @Override
                public String getName() {
                    return "builtin";
                }

                @Override
                public CommandProviderInfo getInfo() {
                    return CommandProviderInfo.builtin("builtin", Collections.singletonList("core"));
                }

                @Override
                public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext providerContext) {
                    seen.set(providerContext);
                    return Collections.singletonList(
                        CommandDescriptor.of("builtin-descriptor", fixedCommand("builtin-output"), CommandMeta.viewer(true, false))
                    );
                }
            };

            new CommandRegistry(
                config,
                null,
                auditLogger,
                Collections.singletonList(provider),
                null,
                context
            );

            Assert.assertSame(context, seen.get());
            Assert.assertNotNull(seen.get().getInstrumentation());
            Assert.assertNotNull(seen.get().getTransformer());
            Assert.assertNotNull(seen.get().getSpyDispatcher());
        }
    }

    @Test
    public void builtinDescriptorWithoutMeta_isRejected() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProvider provider = new TestBuiltinProvider() {
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

    @Test
    public void externalProviderCannotClaimBuiltinNamespace() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandProvider provider = pluginProvider(
                "reserved-provider",
                CommandProviderInfo.plugin(
                    "reserved-provider",
                    "builtin",
                    "1",
                    Collections.singletonList("commands"),
                    true
                ),
                "reserved",
                "reserved-output"
            );

            CommandRegistry registry = new CommandRegistry(
                config,
                null,
                auditLogger,
                Collections.singletonList(provider),
                null,
                minimalContext(config, auditLogger)
            );

            Assert.assertNull(registry.getEntry("builtin:reserved"));
            Assert.assertNotNull(registry.getEntry("reserved-provider:reserved"));
            Assert.assertEquals("reserved-provider", registry.getEntry("reserved").getNamespace());
        }
    }

    @Test
    public void conflictStrategy_defaultsToBuiltinForUnqualifiedCommandNames() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandRegistry registry = new CommandRegistry(
                config,
                null,
                auditLogger,
                Arrays.asList(
                    builtinProvider("builtin-provider", "dup", "builtin-output"),
                    pluginProvider("plugin-provider", CommandProviderInfo.plugin("plugin-provider", "plugin", "1", Collections.singletonList("commands"), true), "dup", "plugin-output")
                ),
                null,
                minimalContext(config, auditLogger)
            );

            Assert.assertEquals("builtin-output", registry.getEntry("dup").getCommand().execute(new String[]{"dup"}));
            Assert.assertEquals("plugin-output", registry.getEntry("plugin:dup").getCommand().execute(new String[]{"dup"}));
        }
    }

    @Test
    public void conflictStrategy_preferPluginKeepsExternalUnqualifiedCommandName() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("plugins.conflict.strategy", "prefer-plugin");
        try (AuditLogger auditLogger = new AuditLogger(config)) {
            CommandRegistry registry = new CommandRegistry(
                config,
                null,
                auditLogger,
                Arrays.asList(
                    builtinProvider("builtin-provider", "dup", "builtin-output"),
                    pluginProvider("plugin-provider", CommandProviderInfo.plugin("plugin-provider", "plugin", "1", Collections.singletonList("commands"), true), "dup", "plugin-output")
                ),
                null,
                minimalContext(config, auditLogger)
            );

            Assert.assertEquals("plugin-output", registry.getEntry("dup").getCommand().execute(new String[]{"dup"}));
            Assert.assertEquals("plugin-output", registry.getEntry("plugin:dup").getCommand().execute(new String[]{"dup"}));
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

    private static CommandProviderContext privilegedContext(ProductionConfig config, AuditLogger auditLogger) {
        return new CommandProviderContext(
            fakeInstrumentation(),
            new SleuthClassFileTransformer(config),
            null,
            config,
            auditLogger,
            null,
            null,
            null,
            null,
            null,
            null,
            new SleuthSpyDispatcher()
        );
    }

    private static CommandProvider builtinProvider(final String name, final String commandName, final String output) {
        return new TestBuiltinProvider() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext providerContext) {
                return Collections.singletonList(
                    CommandDescriptor.of(commandName, fixedCommand(output), CommandMeta.viewer(true, false))
                );
            }
        };
    }

    private static CommandProvider pluginProvider(
        final String name,
        final CommandProviderInfo info,
        final String commandName,
        final String output
    ) {
        return new CommandProvider() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public CommandProviderInfo getInfo() {
                return info;
            }

            @Override
            public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext providerContext) {
                return Collections.singletonList(
                    CommandDescriptor.of(commandName, fixedCommand(output), CommandMeta.viewer(true, false))
                );
            }
        };
    }

    private abstract static class TestBuiltinProvider implements CommandProvider, InternalCommandProvider {
        @Override
        public CommandProviderInfo getInfo() {
            return CommandProviderInfo.builtin(getName(), Collections.singletonList("test"));
        }
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

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return false;
                }
                if (returnType == Integer.TYPE) {
                    return 0;
                }
                if (returnType == Long.TYPE) {
                    return 0L;
                }
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                return null;
            }
        );
    }
}
