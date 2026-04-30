package com.javasleuth.core.command;

import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

public class BuiltinCommandProviderSplitTest {
    @Test
    public void domainProvidersOwnExpectedCommandSets() throws Exception {
        withProviderContext((context) -> {
            assertCommands(
                new JvmDiagnosticsCommandProvider().getCommandDescriptors(context),
                "dashboard",
                "thread",
                "sc",
                "sm",
                "profiler",
                "jvm",
                "memory",
                "getstatic",
                "classloader",
                "mbean",
                "logger"
            );
            assertCommands(
                new EnhancementCommandProvider().getCommandDescriptors(context),
                "watch",
                "trace",
                "tt",
                "jobs",
                "monitor",
                "stack",
                "reset",
                "status",
                "vmtool"
            );
            assertCommands(
                new RuntimeMutationCommandProvider().getCommandDescriptors(context),
                "redefine",
                "mc",
                "retransform",
                "sysprop",
                "sysenv",
                "vmoption",
                "heapdump",
                "dump",
                "jad"
            );
            assertCommands(
                new SecurityOpsCommandProvider().getCommandDescriptors(context),
                "audit",
                "session",
                "perm",
                "auth"
            );
            assertCommands(
                new OperationsCommandProvider().getCommandDescriptors(context),
                "health",
                "metrics",
                "config",
                "version",
                "quit",
                "stop"
            );
        });
    }

    @Test
    public void builtinProviderAggregatesDomainProvidersWithoutDuplicateCommands() throws Exception {
        withProviderContext((context) -> {
            Collection<CommandDescriptor> descriptors = new BuiltinCommandProvider().getCommandDescriptors(context);
            Set<String> names = descriptorNames(descriptors);
            Assert.assertEquals("Built-in provider should not publish duplicate command names", descriptors.size(), names.size());
            Assert.assertEquals(
                new HashSet<>(
                    Arrays.asList(
                        "dashboard",
                        "thread",
                        "sc",
                        "sm",
                        "profiler",
                        "jvm",
                        "memory",
                        "getstatic",
                        "classloader",
                        "mbean",
                        "logger",
                        "watch",
                        "trace",
                        "tt",
                        "jobs",
                        "monitor",
                        "stack",
                        "reset",
                        "status",
                        "vmtool",
                        "redefine",
                        "mc",
                        "retransform",
                        "sysprop",
                        "sysenv",
                        "vmoption",
                        "heapdump",
                        "dump",
                        "jad",
                        "audit",
                        "session",
                        "perm",
                        "auth",
                        "health",
                        "metrics",
                        "config",
                        "version",
                        "quit",
                        "stop"
                    )
                ),
                names
            );
        });
    }

    private static void assertCommands(Collection<CommandDescriptor> descriptors, String... expected) {
        Set<String> names = descriptorNames(descriptors);
        Assert.assertEquals(new HashSet<>(Arrays.asList(expected)), names);
    }

    private static Set<String> descriptorNames(Collection<CommandDescriptor> descriptors) {
        Set<String> names = new LinkedHashSet<>();
        for (CommandDescriptor descriptor : descriptors) {
            names.add(descriptor.getName());
        }
        return names;
    }

    private static void withProviderContext(ContextConsumer consumer) throws Exception {
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
            consumer.accept(context);
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

    private interface ContextConsumer {
        void accept(CommandProviderContext context) throws Exception;
    }
}
