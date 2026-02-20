package com.javasleuth.command.impl;

import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.security.AuthenticationManager;
import org.junit.Test;

import static org.junit.Assert.*;

public class SessionCommandTest {

    @Test
    public void sessionMasksTokenByDefault_andCanShowFullTokenWithFlag() {
        AuthenticationManager auth = AuthenticationManager.getInstance();
        AuthenticationManager.AuthenticationResult session =
            auth.createSession(AuthenticationManager.UserRole.OPERATOR, "test-client");
        assertTrue(session.isSuccess());

        String sessionId = session.getSessionId();
        CommandContext ctx = new CommandContext("c1", "test", sessionId, false, false);
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
}
