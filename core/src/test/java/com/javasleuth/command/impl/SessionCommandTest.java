package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import org.junit.Test;

import static org.junit.Assert.*;

public class SessionCommandTest {

    @Test
    public void sessionMasksTokenByDefault_andCanShowFullTokenWithFlag() {
        String oldAuthEnabled = System.getProperty("sleuth.security.auth.password.enabled");
        String oldOperatorPassword = System.getProperty("sleuth.security.auth.operator.password");
        try {
            System.setProperty("sleuth.security.auth.password.enabled", "true");
            System.setProperty("sleuth.security.auth.operator.password", "secret");

        ProductionConfig config = ProductionConfig.createDefault();
        try (
            AuditLogger auditLogger = new AuditLogger(config);
            AuthenticationManager auth = new AuthenticationManager(config, auditLogger)
        ) {
            AuthenticationManager.AuthenticationResult session =
                auth.authenticate("operator", "secret", "test-client");
            assertTrue(session.isSuccess());

            String sessionId = session.getSessionId();
            CommandContext ctx = new CommandContext("c1", "test", sessionId, false);
            CommandContextHolder.set(ctx);
            try {
                SessionCommand cmd = new SessionCommand(auth);

                String masked = cmd.execute(new String[]{"session"});
                assertNotNull(masked);
                assertFalse(masked.contains(sessionId));
                assertTrue(masked.contains("SessionId="));

                String full = cmd.execute(new String[]{"session", "--show-token"});
                assertTrue(full.contains(sessionId));
            } finally {
                CommandContextHolder.clear();
            }
        }
        } finally {
            setOrClearProperty("sleuth.security.auth.password.enabled", oldAuthEnabled);
            setOrClearProperty("sleuth.security.auth.operator.password", oldOperatorPassword);
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
