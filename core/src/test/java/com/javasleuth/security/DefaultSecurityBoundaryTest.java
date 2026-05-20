package com.javasleuth.security;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.CommandPipeline;
import com.javasleuth.core.command.CommandRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.SecurityConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import org.junit.Test;

import static org.junit.Assert.*;

public class DefaultSecurityBoundaryTest {
    @Test
    public void defaultSecurityConfigEnablesAuthorizationAndRiskConfirmation() {
        ProductionConfig config = ProductionConfig.createDefault();
        SecurityConfig security = config.typedSnapshot().security();

        assertTrue(SleuthConfigSchema.SECURITY_AUTHORIZATION_ENABLED.read(config));
        assertTrue(security.isAuthorizationEnabled());
        assertFalse(security.isAnonymousViewerEnabled());
        assertFalse(security.isPasswordAuthEnabled());
        assertTrue(security.isDangerousConfirmEnabled());
        assertTrue(security.isImpactHighConfirmEnabled());
    }

    @Test
    public void defaultSecurityConfigRejectsAnonymousViewerSession() {
        ProductionConfig config = ProductionConfig.createDefault();
        try (
            AuditLogger auditLogger = new AuditLogger(config);
            AuthenticationManager authn = new AuthenticationManager(config, auditLogger)
        ) {
            AuthenticationManager.AuthenticationResult viewer =
                authn.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
            assertFalse(viewer.isSuccess());
            assertNull(viewer.getSessionId());
            assertTrue(viewer.getMessage().toLowerCase().contains("anonymous viewer"));
        }
    }

    @Test
    public void explicitAnonymousViewerCanRunViewerCommand() {
        String oldAnon = System.getProperty("sleuth.security.anonymous.viewer");
        try {
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger)
            ) {
                AuthenticationManager.AuthenticationResult viewer =
                    authn.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
                assertTrue(viewer.isSuccess());

                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                AuthorizationManager.AuthorizationResult result = authz.authorize(
                    viewer.getSessionId(),
                    "version",
                    new String[]{"version"},
                    CommandMeta.viewer(true, false)
                );

                assertTrue(result.isAllowed());
            }
        } finally {
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnon);
        }
    }

    @Test
    public void explicitAnonymousViewerCannotRunOperatorCommand() {
        String oldAnon = System.getProperty("sleuth.security.anonymous.viewer");
        try {
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger)
            ) {
                AuthenticationManager.AuthenticationResult viewer =
                    authn.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
                assertTrue(viewer.isSuccess());

                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                AuthorizationManager.AuthorizationResult result = authz.authorize(
                    viewer.getSessionId(),
                    "watch",
                    new String[]{"watch", "com.example.Service", "call"},
                    CommandMeta.operator(false, true)
                );

                assertFalse(result.isAllowed());
                assertTrue(result.getReason().toLowerCase().contains("insufficient"));
            }
        } finally {
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnon);
        }
    }

    @Test
    public void credentialFreeSessionCreationOnlyAllowsViewer() {
        String oldAnon = System.getProperty("sleuth.security.anonymous.viewer");
        try {
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger)
            ) {
                AuthenticationManager.AuthenticationResult admin =
                    authn.createSession(AuthenticationManager.UserRole.ADMIN, "test-client");
                AuthenticationManager.AuthenticationResult operator =
                    authn.createSession(AuthenticationManager.UserRole.OPERATOR, "test-client");
                AuthenticationManager.AuthenticationResult viewer =
                    authn.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");

                assertFalse(admin.isSuccess());
                assertFalse(operator.isSuccess());
                assertTrue(viewer.isSuccess());
            }
        } finally {
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnon);
        }
    }

    @Test
    public void dangerousCommandRequiresAdminBeforeConfirmationToken() {
        String oldAnon = System.getProperty("sleuth.security.anonymous.viewer");
        try {
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm =
                    new DangerousCommandConfirmationManager(config, auditLogger);
                PerformanceOptimizer optimizer = new PerformanceOptimizer(config)
            ) {
                AuthenticationManager.AuthenticationResult viewer =
                    authn.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
                assertTrue(viewer.isSuccess());

                CommandPipeline pipeline = new CommandPipeline(
                    new InputValidator(config, auditLogger),
                    new AuthorizationManager(config, auditLogger, authn),
                    dangerousConfirm,
                    config,
                    optimizer
                );
                try {
                    CommandRegistry.Entry entry = new CommandRegistry.Entry(
                        noOpCommand(),
                        CommandMeta.viewer(false, false).withDangerous(true),
                        "test"
                    );
                    CommandPipeline.PrecheckResult result = pipeline.precheck(
                        entry,
                        "dangerous_plugin_cmd",
                        new String[]{"dangerous_plugin_cmd"},
                        new CommandContext("c1", "test-client", viewer.getSessionId(), false)
                    );

                    assertFalse(result.isOk());
                    assertTrue(result.getError().toLowerCase().contains("required: admin"));
                } finally {
                    pipeline.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnon);
        }
    }

    @Test
    public void adminDangerousCommandGetsConfirmationChallengeByDefault() {
        String oldAuthEnabled = System.getProperty("sleuth.security.auth.password.enabled");
        String oldAdminPassword = System.getProperty("sleuth.security.auth.admin.password");
        try {
            System.setProperty("sleuth.security.auth.password.enabled", "true");
            System.setProperty("sleuth.security.auth.admin.password", "secret");

        ProductionConfig config = ProductionConfig.createDefault();
        try (
            AuditLogger auditLogger = new AuditLogger(config);
            AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
            DangerousCommandConfirmationManager dangerousConfirm =
                new DangerousCommandConfirmationManager(config, auditLogger);
            PerformanceOptimizer optimizer = new PerformanceOptimizer(config)
        ) {
            AuthenticationManager.AuthenticationResult admin =
                authn.authenticate("admin", "secret", "test-client");
            assertTrue(admin.isSuccess());

            CommandPipeline pipeline = new CommandPipeline(
                new InputValidator(config, auditLogger),
                new AuthorizationManager(config, auditLogger, authn),
                dangerousConfirm,
                config,
                optimizer
            );
            try {
                CommandRegistry.Entry entry = new CommandRegistry.Entry(
                    noOpCommand(),
                    CommandMeta.admin(false, false).withDangerous(true),
                    "test"
                );
                CommandPipeline.PrecheckResult result = pipeline.precheck(
                    entry,
                    "redefine",
                    new String[]{"redefine", "com.example.Foo", "/tmp/Foo.class"},
                    new CommandContext("c1", "test-client", admin.getSessionId(), false)
                );

                assertFalse(result.isOk());
                assertNotNull(result.getError());
                assertTrue(result.getError().contains("--confirm"));
            } finally {
                pipeline.shutdown();
            }
        }
        } finally {
            setOrClearProperty("sleuth.security.auth.password.enabled", oldAuthEnabled);
            setOrClearProperty("sleuth.security.auth.admin.password", oldAdminPassword);
        }
    }

    @Test
    public void configuredAdminPasswordUpgradesSessionThroughAuthCommand() {
        String oldAuthEnabled = System.getProperty("sleuth.security.auth.password.enabled");
        String oldAdminPassword = System.getProperty("sleuth.security.auth.admin.password");
        String oldAnon = System.getProperty("sleuth.security.anonymous.viewer");
        try {
            System.setProperty("sleuth.security.auth.password.enabled", "true");
            System.setProperty("sleuth.security.auth.admin.password", "secret");
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm =
                    new DangerousCommandConfirmationManager(config, auditLogger);
                PerformanceOptimizer optimizer = new PerformanceOptimizer(config)
            ) {
                AuthenticationManager.AuthenticationResult viewer =
                    authn.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
                assertTrue(viewer.isSuccess());

                CommandPipeline pipeline = new CommandPipeline(
                    new InputValidator(config, auditLogger),
                    new AuthorizationManager(config, auditLogger, authn),
                    dangerousConfirm,
                    config,
                    optimizer
                );
                try {
                    com.javasleuth.core.command.impl.AuthCommand authCommand =
                        new com.javasleuth.core.command.impl.AuthCommand(authn);
                    CommandRegistry.Entry entry = new CommandRegistry.Entry(
                        authCommand,
                        CommandMeta.viewer(false, false),
                        "test"
                    );
                    CommandContext context =
                        new CommandContext("c1", "test-client", viewer.getSessionId(), false);

                    CommandPipeline.Result result;
                    try {
                        CommandContextHolder.set(context);
                        result = pipeline.execute(entry, new String[]{"auth", "admin", "secret"}, context);
                    } finally {
                        CommandContextHolder.clear();
                    }

                    assertTrue(result.isSuccess());
                    assertTrue(result.getOutput().contains("Authenticated as admin"));
                    AuthenticationManager.SessionValidationResult session =
                        authn.validateSession(context.getSessionId());
                    assertTrue(session.isValid());
                    assertEquals(AuthenticationManager.UserRole.ADMIN, session.getRole());
                } finally {
                    pipeline.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.auth.password.enabled", oldAuthEnabled);
            setOrClearProperty("sleuth.security.auth.admin.password", oldAdminPassword);
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnon);
        }
    }

    @Test
    public void authenticationFailsWhenPasswordAuthDisabledByDefault() {
        ProductionConfig config = ProductionConfig.createDefault();
        try (
            AuditLogger auditLogger = new AuditLogger(config);
            AuthenticationManager authn = new AuthenticationManager(config, auditLogger)
        ) {
            AuthenticationManager.AuthenticationResult result =
                authn.authenticate("admin", "anything", "test-client");

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().toLowerCase().contains("disabled"));
        }
    }

    private static Command noOpCommand() {
        return new Command() {
            @Override
            public String execute(String[] args) {
                return "ok";
            }

            @Override
            public String getDescription() {
                return "noop";
            }
        };
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
