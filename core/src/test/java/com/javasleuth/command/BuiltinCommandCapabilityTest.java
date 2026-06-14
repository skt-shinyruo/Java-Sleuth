package com.javasleuth.core.command;

import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class BuiltinCommandCapabilityTest {
    @Test
    public void instrumentationCommandsDeclareBootstrapAndLongRunningCapabilities() throws Exception {
        withBuiltinMeta((metaByName) -> {
            assertInstrumentationCommand(metaByName, "watch");
            assertInstrumentationCommand(metaByName, "trace");
            assertInstrumentationCommand(metaByName, "monitor");
            assertInstrumentationCommand(metaByName, "tt");
            assertInstrumentationCommand(metaByName, "stack");
            assertInstrumentationCommand(metaByName, "vmtool");
        });
    }

    @Test
    public void nonInstrumentationCommandsDoNotRequireBootstrapBridge() throws Exception {
        withBuiltinMeta((metaByName) -> {
            assertNoBootstrapRequirement(metaByName, "thread");
            assertNoBootstrapRequirement(metaByName, "jvm");
            assertNoBootstrapRequirement(metaByName, "jobs");
            assertNoBootstrapRequirement(metaByName, "status");
        });
    }

    @Test
    public void diskWritingCommandsDeclareWritesDiskCapability() throws Exception {
        withBuiltinMeta((metaByName) -> {
            assertCapability(metaByName, "heapdump", CommandCapability.WRITES_DISK);
            assertCapability(metaByName, "dump", CommandCapability.WRITES_DISK);
            assertCapability(metaByName, "mc", CommandCapability.WRITES_DISK);
        });
    }

    @Test
    public void mutatingDiagnosticSubcommandsRequireAdminRole() throws Exception {
        withBuiltinMeta((metaByName) -> {
            assertRequiredRole(metaByName, "mbean", new String[]{"mbean", "set"}, UserRole.ADMIN);
            assertRequiredRole(metaByName, "mbean", new String[]{"mbean", "invoke"}, UserRole.ADMIN);
            assertRequiredRole(metaByName, "logger", new String[]{"logger", "set"}, UserRole.ADMIN);
        });
    }

    private static void assertInstrumentationCommand(Map<String, CommandMeta> metaByName, String commandName) {
        CommandMeta meta = requireMeta(metaByName, commandName);
        Assert.assertTrue(commandName + " should require bootstrap", meta.requiresBootstrap());
        Assert.assertTrue(
            commandName + " should require SleuthSpyAPI",
            meta.getRequiredBootstrapClasses().contains(BootstrapBridge.SPY_API)
        );
        Assert.assertTrue(
            commandName + " should use instrumentation",
            meta.hasCapability(CommandCapability.USES_INSTRUMENTATION)
        );
        Assert.assertTrue(
            commandName + " should be long running",
            meta.hasCapability(CommandCapability.LONG_RUNNING)
        );
    }

    private static void assertNoBootstrapRequirement(Map<String, CommandMeta> metaByName, String commandName) {
        CommandMeta meta = requireMeta(metaByName, commandName);
        Assert.assertFalse(commandName + " should not require bootstrap", meta.requiresBootstrap());
        Assert.assertTrue(
            commandName + " should have no bootstrap classes",
            meta.getRequiredBootstrapClasses().isEmpty()
        );
    }

    private static void assertCapability(
        Map<String, CommandMeta> metaByName,
        String commandName,
        CommandCapability capability
    ) {
        CommandMeta meta = requireMeta(metaByName, commandName);
        Assert.assertTrue(commandName + " should declare " + capability, meta.hasCapability(capability));
    }

    private static void assertRequiredRole(
        Map<String, CommandMeta> metaByName,
        String commandName,
        String[] args,
        UserRole expected
    ) {
        CommandMeta meta = requireMeta(metaByName, commandName);
        Assert.assertEquals(expected, meta.getRequiredRoleForArgs(args));
    }

    private static CommandMeta requireMeta(Map<String, CommandMeta> metaByName, String commandName) {
        CommandMeta meta = metaByName.get(commandName);
        Assert.assertNotNull("Missing built-in command metadata for " + commandName, meta);
        return meta;
    }

    private static void withBuiltinMeta(MetaConsumer consumer) throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        Instrumentation instrumentation = fakeInstrumentation();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        MetricsCollector metricsCollector = new MetricsCollector(config);
        JobManager jobManager = new JobManager();
        SleuthSpyDispatcher spyDispatcher = new SleuthSpyDispatcher();
        VmToolSessionRegistry vmToolSessionRegistry = new VmToolSessionRegistry(spyDispatcher);
        PerformanceOptimizer performanceOptimizer = new PerformanceOptimizer(config);
        try (
            AuditLogger auditLogger = new AuditLogger(config);
            AuthenticationManager authenticationManager = new AuthenticationManager(config, auditLogger);
            DangerousCommandConfirmationManager dangerousConfirm =
                new DangerousCommandConfirmationManager(config, auditLogger)
        ) {
            CommandProviderContext context = new CommandProviderContext(
                instrumentation,
                transformer,
                metricsCollector,
                config,
                auditLogger,
                null,
                authenticationManager,
                dangerousConfirm,
                jobManager,
                vmToolSessionRegistry,
                performanceOptimizer,
                spyDispatcher,
                new EnhancementSessionRegistry()
            );

            Collection<CommandDescriptor> descriptors = new BuiltinCommandProvider().getCommandDescriptors(context);
            Map<String, CommandMeta> metaByName = new LinkedHashMap<>();
            for (CommandDescriptor descriptor : descriptors) {
                metaByName.put(descriptor.getName(), descriptor.getMeta());
            }
            consumer.accept(metaByName);
        } finally {
            vmToolSessionRegistry.shutdown(instrumentation, transformer, "test cleanup");
            jobManager.shutdown("test cleanup");
            metricsCollector.shutdown();
            performanceOptimizer.close();
        }
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return new Class<?>[0];
                }
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("removeTransformer".equals(name)) {
                    return true;
                }
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

    private interface MetaConsumer {
        void accept(Map<String, CommandMeta> metaByName) throws Exception;
    }
}
