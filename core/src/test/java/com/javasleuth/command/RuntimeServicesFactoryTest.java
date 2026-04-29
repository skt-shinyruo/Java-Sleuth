package com.javasleuth.core.command;

import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import org.junit.Assert;
import org.junit.Test;

public class RuntimeServicesFactoryTest {

    @Test
    public void create_usesDefaultsAndMarksCreatedResourcesOwned() {
        RuntimeServices services = RuntimeServicesFactory.create(
            CommandProcessorFactoryRequest.builder(
                fakeInstrumentation(),
                new SleuthClassFileTransformer(ProductionConfig.createDefault())
            ).build()
        );

        try {
            Assert.assertNotNull(services.config);
            Assert.assertNotNull(services.typedConfig);
            Assert.assertNotNull(services.auditLogger);
            Assert.assertNotNull(services.inputValidator);
            Assert.assertNotNull(services.authenticationManager);
            Assert.assertNotNull(services.authorizationManager);
            Assert.assertNotNull(services.dangerousConfirm);
            Assert.assertNotNull(services.clientSessionRegistry);
            Assert.assertNotNull(services.metricsCollector);
            Assert.assertNotNull(services.jobManager);
            Assert.assertNotNull(services.vmToolSessionRegistry);
            Assert.assertNotNull(services.performanceOptimizer);
            Assert.assertNotNull(services.spyDispatcher);
            Assert.assertNotNull(services.enhancementSessionRegistry);

            Assert.assertTrue(services.ownership.ownsAuditLogger());
            Assert.assertTrue(services.ownership.ownsAuthenticationManager());
            Assert.assertTrue(services.ownership.ownsDangerousConfirm());
            Assert.assertTrue(services.ownership.ownsPerformanceOptimizer());
            Assert.assertTrue(services.ownership.ownsVmToolSessionRegistry());
            Assert.assertTrue(services.ownership.ownsClientSessionRegistry());
            Assert.assertTrue(services.ownership.ownsJobManager());
            Assert.assertTrue(services.ownership.ownsEnhancementSessionRegistry());
        } finally {
            closeQuietly(ResourceCloser.forOwnedResources(services));
        }
    }

    @Test
    public void create_reusesInjectedServicesAndDoesNotOwnThem() {
        ProductionConfig config = ProductionConfig.createDefault();
        AuditLogger audit = new AuditLogger(config);
        AuthenticationManager authn = new AuthenticationManager(config, audit);
        AuthorizationManager authz = new AuthorizationManager(config, audit, authn);
        DangerousCommandConfirmationManager dangerous = new DangerousCommandConfirmationManager(config, audit);
        ClientSessionRegistry clientSessions = new ClientSessionRegistry();
        MetricsCollector metrics = new MetricsCollector(config);
        JobManager jobs = new JobManager();
        SleuthSpyDispatcher spyDispatcher = new SleuthSpyDispatcher();
        VmToolSessionRegistry vmtool = new VmToolSessionRegistry(spyDispatcher);
        PerformanceOptimizer perf = new PerformanceOptimizer(config);
        EnhancementSessionRegistry enhancements = new EnhancementSessionRegistry();

        try {
            RuntimeServices services = RuntimeServicesFactory.create(
                CommandProcessorFactoryRequest.builder(fakeInstrumentation(), new SleuthClassFileTransformer(config))
                    .withConfig(config)
                    .withAuditLogger(audit)
                    .withAuthenticationManager(authn)
                    .withAuthorizationManager(authz)
                    .withDangerousConfirm(dangerous)
                    .withClientSessionRegistry(clientSessions)
                    .withMetricsCollector(metrics)
                    .withJobManager(jobs)
                    .withVmToolSessionRegistry(vmtool)
                    .withPerformanceOptimizer(perf)
                    .withSpyDispatcher(spyDispatcher)
                    .withEnhancementSessionRegistry(enhancements)
                    .build()
            );

            Assert.assertSame(config, services.config);
            Assert.assertSame(audit, services.auditLogger);
            Assert.assertSame(authn, services.authenticationManager);
            Assert.assertSame(authz, services.authorizationManager);
            Assert.assertSame(dangerous, services.dangerousConfirm);
            Assert.assertSame(clientSessions, services.clientSessionRegistry);
            Assert.assertSame(metrics, services.metricsCollector);
            Assert.assertSame(jobs, services.jobManager);
            Assert.assertSame(vmtool, services.vmToolSessionRegistry);
            Assert.assertSame(perf, services.performanceOptimizer);
            Assert.assertSame(spyDispatcher, services.spyDispatcher);
            Assert.assertSame(enhancements, services.enhancementSessionRegistry);
            Assert.assertFalse(services.ownership.hasOwnedResources());
            Assert.assertNull(ResourceCloser.forOwnedResources(services));
        } finally {
            closeQuietly(enhancements);
            closeQuietly(new AutoCloseable() {
                @Override
                public void close() {
                    jobs.shutdown("test");
                }
            });
            closeQuietly(new AutoCloseable() {
                @Override
                public void close() {
                    clientSessions.shutdown("test");
                }
            });
            closeQuietly(new AutoCloseable() {
                @Override
                public void close() {
                    vmtool.shutdown(fakeInstrumentation(), new SleuthClassFileTransformer(config), "test");
                }
            });
            closeQuietly(new AutoCloseable() {
                @Override
                public void close() {
                    metrics.shutdown();
                }
            });
            closeQuietly(perf);
            closeQuietly(dangerous);
            closeQuietly(authn);
            closeQuietly(new AutoCloseable() {
                @Override
                public void close() {
                    authz.shutdown();
                }
            });
            closeQuietly(audit);
        }
    }

    @Test
    public void create_rejectsMissingRequiredInputs() {
        ProductionConfig config = ProductionConfig.createDefault();
        try {
            RuntimeServicesFactory.create(
                CommandProcessorFactoryRequest.builder(null, new SleuthClassFileTransformer(config)).build()
            );
            Assert.fail("Expected instrumentation validation failure");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("instrumentation"));
        }

        try {
            RuntimeServicesFactory.create(CommandProcessorFactoryRequest.builder(fakeInstrumentation(), null).build());
            Assert.fail("Expected transformer validation failure");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("transformer"));
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignore) {
            // best-effort test cleanup
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
}
