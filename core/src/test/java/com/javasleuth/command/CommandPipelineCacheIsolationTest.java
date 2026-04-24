package com.javasleuth.core.command;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class CommandPipelineCacheIsolationTest {

    @Test
    public void cacheKeyIncludesClientId_toAvoidCrossClientLeakage() {
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        String oldValidation = System.getProperty("sleuth.security.input.validation");

        try {
            System.setProperty("sleuth.security.authorization.enabled", "false");
            System.setProperty("sleuth.security.input.validation", "false");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger);
                PerformanceOptimizer optimizer = new PerformanceOptimizer(config)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                InputValidator validator = new InputValidator(config, auditLogger);
                optimizer.clearCache();
                CommandPipeline pipeline = new CommandPipeline(validator, authz, dangerousConfirm, config, optimizer);
                try {

            AtomicInteger seq = new AtomicInteger(0);
            Command cmd = new Command() {
                @Override
                public String execute(String[] args) {
                    return "v=" + seq.incrementAndGet();
                }

                @Override
                public String getDescription() {
                    return "counter";
                }
            };

            CommandRegistry.Entry entry = new CommandRegistry.Entry(cmd, CommandMeta.viewer(true, false), "test");

            CommandPipeline.Result r1 = pipeline.execute(entry, new String[]{"foo"}, new CommandContext("c1", "i1", "s1", false));
            CommandPipeline.Result r2 = pipeline.execute(entry, new String[]{"foo"}, new CommandContext("c1", "i1", "s1", false));
            CommandPipeline.Result r3 = pipeline.execute(entry, new String[]{"foo"}, new CommandContext("c2", "i2", "s2", false));

            assertTrue(r1.isSuccess());
            assertTrue(r2.isSuccess());
            assertTrue(r3.isSuccess());

            assertEquals(r1.getOutput(), r2.getOutput());
            assertNotEquals(r1.getOutput(), r3.getOutput());
            assertEquals("v=1", r1.getOutput());
            assertEquals("v=2", r3.getOutput());
                } finally {
                    pipeline.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.authorization.enabled", oldAuthz);
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    @Test
    public void dashboardRealtimeBypassesCache_evenWhenMetaCacheable() {
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        String oldValidation = System.getProperty("sleuth.security.input.validation");

        try {
            System.setProperty("sleuth.security.authorization.enabled", "false");
            System.setProperty("sleuth.security.input.validation", "false");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger);
                PerformanceOptimizer optimizer = new PerformanceOptimizer(config)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                InputValidator validator = new InputValidator(config, auditLogger);
                optimizer.clearCache();
                CommandPipeline pipeline = new CommandPipeline(validator, authz, dangerousConfirm, config, optimizer);
                try {

            AtomicInteger seq = new AtomicInteger(0);
            Command cmd = new Command() {
                @Override
                public String execute(String[] args) {
                    return "v=" + seq.incrementAndGet();
                }

                @Override
                public String getDescription() {
                    return "counter";
                }
            };

            CommandRegistry.Entry entry = new CommandRegistry.Entry(cmd, CommandMeta.viewer(true, false), "test");

            CommandContext ctx = new CommandContext("c1", "i1", "s1", false);

            CommandPipeline.Result r1 = pipeline.execute(entry, new String[]{"dashboard", "realtime"}, ctx);
            CommandPipeline.Result r2 = pipeline.execute(entry, new String[]{"dashboard", "realtime"}, ctx);

            assertTrue(r1.isSuccess());
            assertTrue(r2.isSuccess());
            assertNotEquals("realtime should bypass cache", r1.getOutput(), r2.getOutput());
            assertEquals("v=1", r1.getOutput());
            assertEquals("v=2", r2.getOutput());
                } finally {
                    pipeline.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.authorization.enabled", oldAuthz);
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    @Test
    public void cacheDoesNotLeakAcrossPipelineInstances() {
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        String oldValidation = System.getProperty("sleuth.security.input.validation");

        try {
            System.setProperty("sleuth.security.authorization.enabled", "false");
            System.setProperty("sleuth.security.input.validation", "false");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger);
                PerformanceOptimizer optimizer1 = new PerformanceOptimizer(config);
                PerformanceOptimizer optimizer2 = new PerformanceOptimizer(config)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                InputValidator validator = new InputValidator(config, auditLogger);
                CommandPipeline pipeline1 = new CommandPipeline(validator, authz, dangerousConfirm, config, optimizer1);
                CommandPipeline pipeline2 = new CommandPipeline(validator, authz, dangerousConfirm, config, optimizer2);
                try {
                    AtomicInteger seq1 = new AtomicInteger(0);
                    AtomicInteger seq2 = new AtomicInteger(0);

                    Command cmd1 = new Command() {
                        @Override
                        public String execute(String[] args) {
                            return "p1=" + seq1.incrementAndGet();
                        }

                        @Override
                        public String getDescription() {
                            return "pipeline-1-counter";
                        }
                    };

                    Command cmd2 = new Command() {
                        @Override
                        public String execute(String[] args) {
                            return "p2=" + seq2.incrementAndGet();
                        }

                        @Override
                        public String getDescription() {
                            return "pipeline-2-counter";
                        }
                    };

                    CommandRegistry.Entry entry1 = new CommandRegistry.Entry(cmd1, CommandMeta.viewer(true, false), "test");
                    CommandRegistry.Entry entry2 = new CommandRegistry.Entry(cmd2, CommandMeta.viewer(true, false), "test");
                    CommandContext ctx = new CommandContext("c1", "i1", "s1", false);

                    CommandPipeline.Result r1 = pipeline1.execute(entry1, new String[]{"foo"}, ctx);
                    CommandPipeline.Result r2 = pipeline2.execute(entry2, new String[]{"foo"}, ctx);

                    assertTrue(r1.isSuccess());
                    assertTrue(r2.isSuccess());
                    assertEquals("p1=1", r1.getOutput());
                    assertEquals("p2=1", r2.getOutput());
                } finally {
                    pipeline1.shutdown();
                    pipeline2.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.authorization.enabled", oldAuthz);
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
