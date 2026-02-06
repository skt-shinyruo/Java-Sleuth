package com.javasleuth.security;

import com.javasleuth.command.CommandMeta;
import org.junit.Test;

import static org.junit.Assert.*;

public class DangerousCommandConfirmationManagerTest {

    @Test
    public void dangerousCommandRequiresOneTimeToken() {
        System.setProperty("sleuth.security.dangerous.confirm.enabled", "true");
        System.setProperty("sleuth.security.dangerous.confirm.ttl.ms", "60000");
        System.setProperty("sleuth.security.impact.high.confirm.enabled", "true");

        DangerousCommandConfirmationManager mgr = DangerousCommandConfirmationManager.getInstance();
        CommandMeta meta = CommandMeta.admin(false, false).withDangerous(true);

        String sessionId = "s-" + System.nanoTime();
        String marker = "--x=" + System.nanoTime();
        String[] baseArgs = new String[]{"redefine", "com.example.Foo", "/tmp/Foo.class", marker};

        DangerousCommandConfirmationManager.ConfirmationResult first =
            mgr.confirmIfRequired(sessionId, "client", "redefine", baseArgs, meta);
        assertFalse(first.isAllowed());
        assertNotNull(first.getError());
        assertNotNull(first.getNormalizedArgs());

        String token = extractToken(first.getError());
        assertNotNull(token);

        String[] confirmArgs = new String[]{"redefine", "--confirm", token, "com.example.Foo", "/tmp/Foo.class", marker};
        DangerousCommandConfirmationManager.ConfirmationResult second =
            mgr.confirmIfRequired(sessionId, "client", "redefine", confirmArgs, meta);
        assertTrue(second.isAllowed());
        assertNotNull(second.getNormalizedArgs());
        for (String a : second.getNormalizedArgs()) {
            assertNotEquals("--confirm", a);
        }

        // Token is one-time. Reusing it should not allow execution.
        DangerousCommandConfirmationManager.ConfirmationResult third =
            mgr.confirmIfRequired(sessionId, "client", "redefine", confirmArgs, meta);
        assertFalse(third.isAllowed());
    }

    @Test
    public void highImpactCommandAlsoRequiresOneTimeToken_whenEnabled() {
        System.setProperty("sleuth.security.dangerous.confirm.enabled", "true");
        System.setProperty("sleuth.security.dangerous.confirm.ttl.ms", "60000");
        System.setProperty("sleuth.security.impact.high.confirm.enabled", "true");

        DangerousCommandConfirmationManager mgr = DangerousCommandConfirmationManager.getInstance();
        CommandMeta meta = CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.HIGH);

        String sessionId = "s-" + System.nanoTime();
        String marker = "--x=" + System.nanoTime();
        String[] baseArgs = new String[]{"jad", "java.lang.String", marker};

        DangerousCommandConfirmationManager.ConfirmationResult first =
            mgr.confirmIfRequired(sessionId, "client", "jad", baseArgs, meta);
        assertFalse(first.isAllowed());
        assertNotNull(first.getError());
        assertNotNull(first.getNormalizedArgs());

        String token = extractToken(first.getError());
        assertNotNull(token);

        String[] confirmArgs = new String[]{"jad", "--confirm", token, "java.lang.String", marker};
        DangerousCommandConfirmationManager.ConfirmationResult second =
            mgr.confirmIfRequired(sessionId, "client", "jad", confirmArgs, meta);
        assertTrue(second.isAllowed());

        // Token is one-time. Reusing it should not allow execution.
        DangerousCommandConfirmationManager.ConfirmationResult third =
            mgr.confirmIfRequired(sessionId, "client", "jad", confirmArgs, meta);
        assertFalse(third.isAllowed());
    }

    private static String extractToken(String msg) {
        if (msg == null) {
            return null;
        }
        int idx = msg.lastIndexOf("--confirm ");
        if (idx < 0) {
            return null;
        }
        String tail = msg.substring(idx + "--confirm ".length()).trim();
        if (tail.isEmpty()) {
            return null;
        }
        int end = tail.indexOf('\n');
        String token = (end > 0) ? tail.substring(0, end).trim() : tail;
        if ("<token>".equalsIgnoreCase(token)) {
            return null;
        }
        return token;
    }
}
