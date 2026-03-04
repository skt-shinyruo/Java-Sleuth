package com.javasleuth.foundation.security;

import com.javasleuth.foundation.config.ProductionConfig;
import org.junit.Test;

import static org.junit.Assert.*;

public class AuthenticationManagerTest {

    @Test
    public void testLockoutIsScopedByClientKey() {
        String oldEnabled = System.getProperty("sleuth.security.auth.password.enabled");
        String oldAdminPassword = System.getProperty("sleuth.security.auth.admin.password");

        String client1 = "/127.0.0.1:11111";
        String client2 = "/192.168.0.1:22222";

        try {
            System.setProperty("sleuth.security.auth.password.enabled", "true");
            System.setProperty("sleuth.security.auth.admin.password", "test-password");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager mgr = new AuthenticationManager(config, auditLogger)
            ) {
                for (int i = 0; i < 5; i++) {
                    AuthenticationManager.AuthenticationResult r = mgr.authenticate("admin", "wrong", client1);
                    assertFalse(r.isSuccess());
                }

                AuthenticationManager.AuthenticationResult locked = mgr.authenticate("admin", "wrong", client1);
                assertFalse(locked.isSuccess());
                assertNotNull(locked.getMessage());
                assertTrue(locked.getMessage().toLowerCase().contains("locked"));

                AuthenticationManager.AuthenticationResult otherClient = mgr.authenticate("admin", "wrong", client2);
                assertFalse(otherClient.isSuccess());
                assertNotNull(otherClient.getMessage());
                assertTrue(otherClient.getMessage().toLowerCase().contains("invalid"));
            }
        } finally {
            setOrClearProperty("sleuth.security.auth.password.enabled", oldEnabled);
            setOrClearProperty("sleuth.security.auth.admin.password", oldAdminPassword);
        }
    }

    @Test
    public void testIpv6ClientInfoDoesNotProduceEmptyKey() {
        String oldEnabled = System.getProperty("sleuth.security.auth.password.enabled");
        String oldAdminPassword = System.getProperty("sleuth.security.auth.admin.password");

        try {
            System.setProperty("sleuth.security.auth.password.enabled", "true");
            System.setProperty("sleuth.security.auth.admin.password", "test-password");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager mgr = new AuthenticationManager(config, auditLogger)
            ) {
                // Should not throw and should treat it as a distinct key.
                AuthenticationManager.AuthenticationResult r = mgr.authenticate("admin", "wrong", "/[::1]:33333");
                assertFalse(r.isSuccess());
            }
        } finally {
            setOrClearProperty("sleuth.security.auth.password.enabled", oldEnabled);
            setOrClearProperty("sleuth.security.auth.admin.password", oldAdminPassword);
        }
    }

    @Test
    public void shutdownIsIdempotent_andManagerIsRestartable() {
        String oldEnabled = System.getProperty("sleuth.security.auth.password.enabled");
        String oldAdminPassword = System.getProperty("sleuth.security.auth.admin.password");

        try {
            System.setProperty("sleuth.security.auth.password.enabled", "true");
            System.setProperty("sleuth.security.auth.admin.password", "test-password");

            ProductionConfig config = ProductionConfig.createDefault();
            AuditLogger auditLogger1 = new AuditLogger(config);
            AuthenticationManager mgr1 = new AuthenticationManager(config, auditLogger1);

            // Shutdown twice should be safe.
            mgr1.shutdown();
            mgr1.shutdown();
            auditLogger1.shutdown();

            try (
                AuditLogger auditLogger2 = new AuditLogger(config);
                AuthenticationManager mgr2 = new AuthenticationManager(config, auditLogger2)
            ) {
                AuthenticationManager.AuthenticationResult r = mgr2.authenticate("admin", "wrong", "/127.0.0.1:33333");
                assertFalse(r.isSuccess());
            }
        } finally {
            setOrClearProperty("sleuth.security.auth.password.enabled", oldEnabled);
            setOrClearProperty("sleuth.security.auth.admin.password", oldAdminPassword);
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
