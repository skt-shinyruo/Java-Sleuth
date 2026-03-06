package com.javasleuth.core.command;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import org.junit.Test;

import static org.junit.Assert.*;

public class CommandErrorHandlingTest {

    @Test
    public void testCommandErrorMapperRedactsSensitiveKeyValues() {
        RuntimeException ex = new RuntimeException("token=tok_123456 password=supersecret");
        CommandContext ctx = new CommandContext(
            "c1",
            "client",
            "s1",
            "conn-abc",
            "test",
            false,
            null
        );

        String msg = CommandErrorMapper.toUserMessage(ex, "EID123", ctx);
        assertNotNull(msg);
        assertFalse(msg.contains("tok_123456"));
        assertFalse(msg.contains("supersecret"));
        assertTrue(msg.contains("errorId=EID123"));
        assertTrue(msg.contains("connId=conn-abc"));
        assertFalse(msg.contains("\n"));
        assertFalse(msg.contains("\r"));
    }

    @Test
    public void testPipelineDoesNotLeakSensitiveExceptionMessage() {
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        try {
            System.setProperty("sleuth.security.authorization.enabled", "false");
            System.setProperty("sleuth.security.input.validation", "false");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                InputValidator validator = new InputValidator(config, auditLogger);
                CommandPipeline pipeline = new CommandPipeline(validator, authz, dangerousConfirm, config);
                try {

            Command boom = new Command() {
                @Override
                public String execute(String[] args) {
                    throw new RuntimeException("security.auth.admin.password=supersecret");
                }

                @Override
                public String getDescription() {
                    return "boom";
                }
            };

            CommandRegistry.Entry entry = new CommandRegistry.Entry(boom, CommandMeta.viewer(false, false), "test");
            CommandContext context = new CommandContext("c1", "test", null, false);
            CommandPipeline.Result result = pipeline.execute(entry, new String[] { "boom" }, context);
            assertFalse(result.isSuccess());
            assertNotNull(result.getError());
            assertFalse(result.getError().contains("supersecret"));
            assertTrue(result.getError().contains("errorId="));
            assertFalse(result.getError().contains("\n"));
            assertFalse(result.getError().contains("\r"));
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
}
