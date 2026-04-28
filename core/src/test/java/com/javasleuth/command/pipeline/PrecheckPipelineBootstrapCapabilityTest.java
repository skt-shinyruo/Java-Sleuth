package com.javasleuth.command.pipeline;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandPipeline;
import com.javasleuth.core.command.CommandRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import org.junit.Assert;
import org.junit.Test;

public class PrecheckPipelineBootstrapCapabilityTest {
    @Test
    public void bootstrapRequirementFromMetaDeniesNonHelpInvocation() throws Exception {
        withPipeline((pipeline) -> {
            CommandRegistry.Entry entry = new CommandRegistry.Entry(
                fixedCommand(),
                CommandMeta.operator(false, false).requiresBootstrap("missing.TestBridge"),
                "test"
            );

            CommandPipeline.PrecheckResult result = pipeline.precheck(
                entry,
                "custom",
                new String[]{"custom", "run"},
                new CommandContext("client", "test", null, false)
            );

            Assert.assertFalse(result.isOk());
            Assert.assertTrue(result.getError().contains("missing.TestBridge"));
            Assert.assertTrue(result.getError().contains("custom"));
        });
    }

    @Test
    public void bootstrapRequirementFromMetaAllowsHelpInvocation() throws Exception {
        withPipeline((pipeline) -> {
            CommandRegistry.Entry entry = new CommandRegistry.Entry(
                fixedCommand(),
                CommandMeta.operator(false, false).requiresBootstrap("missing.TestBridge"),
                "test"
            );

            CommandPipeline.PrecheckResult result = pipeline.precheck(
                entry,
                "custom",
                new String[]{"custom", "--help"},
                new CommandContext("client", "test", null, false)
            );

            Assert.assertTrue(result.isOk());
        });
    }

    @Test
    public void commandNameAloneNoLongerTriggersBootstrapCheck() throws Exception {
        withPipeline((pipeline) -> {
            CommandRegistry.Entry entry = new CommandRegistry.Entry(
                fixedCommand(),
                CommandMeta.operator(false, false),
                "test"
            );

            CommandPipeline.PrecheckResult result = pipeline.precheck(
                entry,
                "watch",
                new String[]{"watch", "run"},
                new CommandContext("client", "test", null, false)
            );

            Assert.assertTrue(result.isOk());
        });
    }

    private static Command fixedCommand() {
        return new Command() {
            @Override
            public String execute(String[] args) {
                return "ok";
            }

            @Override
            public String getDescription() {
                return "fixed";
            }
        };
    }

    private static void withPipeline(PipelineConsumer consumer) throws Exception {
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
                CommandPipeline pipeline = new CommandPipeline(validator, authz, dangerousConfirm, config, optimizer);
                try {
                    consumer.accept(pipeline);
                } finally {
                    pipeline.shutdown();
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

    private interface PipelineConsumer {
        void accept(CommandPipeline pipeline) throws Exception;
    }
}
