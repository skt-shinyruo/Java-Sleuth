package com.javasleuth.security;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class AuthorizationManagerTest {
    @Test
    public void logoutClearsRateLimitBucketsForSession() throws Exception {
        String oldAnonymousViewer = System.getProperty("sleuth.security.anonymous.viewer");
        try {
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                try {
                    AuthenticationManager.AuthenticationResult session =
                        authn.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
                    Assert.assertTrue(session.isSuccess());

                    CommandMeta meta = CommandMeta.viewer(false, false).withRateLimit(1);
                    AuthorizationManager.AuthorizationResult first = authz.authorize(
                        session.getSessionId(),
                        "version",
                        new String[]{"version"},
                        meta
                    );

                    Assert.assertTrue(first.isAllowed());
                    Assert.assertEquals(1, rateLimitBucketCount(authz));

                    authn.logout(session.getSessionId());

                    Assert.assertEquals(0, rateLimitBucketCount(authz));
                } finally {
                    authz.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnonymousViewer);
        }
    }

    @Test
    public void disabledRateLimitDoesNotAllocateEmptyBucket() throws Exception {
        String oldAnonymousViewer = System.getProperty("sleuth.security.anonymous.viewer");
        try {
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                try {
                    AuthenticationManager.AuthenticationResult session =
                        authn.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
                    Assert.assertTrue(session.isSuccess());

                    CommandMeta meta = CommandMeta.viewer(false, false).withRateLimit(0);
                    AuthorizationManager.AuthorizationResult result = authz.authorize(
                        session.getSessionId(),
                        "version",
                        new String[]{"version"},
                        meta
                    );

                    Assert.assertTrue(result.isAllowed());
                    Assert.assertEquals(0, rateLimitBucketCount(authz));
                } finally {
                    authz.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnonymousViewer);
        }
    }

    @Test
    public void expiredSessionValidationClearsRateLimitBucketsForSession() throws Exception {
        String oldAnonymousViewer = System.getProperty("sleuth.security.anonymous.viewer");
        try {
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                try {
                    AuthenticationManager.AuthenticationResult session =
                        authn.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
                    Assert.assertTrue(session.isSuccess());

                    CommandMeta meta = CommandMeta.viewer(false, false).withRateLimit(1);
                    Assert.assertTrue(authz.authorize(
                        session.getSessionId(),
                        "version",
                        new String[]{"version"},
                        meta
                    ).isAllowed());
                    Assert.assertEquals(1, rateLimitBucketCount(authz));

                    expireSession(authn, session.getSessionId());

                    AuthorizationManager.AuthorizationResult expired = authz.authorize(
                        session.getSessionId(),
                        "version",
                        new String[]{"version"},
                        meta
                    );

                    Assert.assertFalse(expired.isAllowed());
                    Assert.assertEquals(0, rateLimitBucketCount(authz));
                } finally {
                    authz.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnonymousViewer);
        }
    }

    private static int rateLimitBucketCount(AuthorizationManager authz) throws Exception {
        Field f = AuthorizationManager.class.getDeclaredField("rateLimits");
        f.setAccessible(true);
        return ((Map<?, ?>) f.get(authz)).size();
    }

    private static void expireSession(AuthenticationManager authn, String sessionId) throws Exception {
        Object sessionInfo = authn.getSessionInfo(sessionId);
        Assert.assertNotNull(sessionInfo);
        Field lastActivity = sessionInfo.getClass().getDeclaredField("lastActivity");
        lastActivity.setAccessible(true);
        lastActivity.set(sessionInfo, Instant.now().minus(61, ChronoUnit.MINUTES));
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
