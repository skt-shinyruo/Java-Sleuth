package com.javasleuth.security;

import org.junit.Test;

import static org.junit.Assert.*;

public class AuthenticationManagerTest {

    @Test
    public void testLockoutIsScopedByClientKey() {
        AuthenticationManager mgr = AuthenticationManager.getInstance();

        String client1 = "/127.0.0.1:11111";
        String client2 = "/192.168.0.1:22222";

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

    @Test
    public void testIpv6ClientInfoDoesNotProduceEmptyKey() {
        AuthenticationManager mgr = AuthenticationManager.getInstance();

        // Should not throw and should treat it as a distinct key.
        AuthenticationManager.AuthenticationResult r = mgr.authenticate("admin", "wrong", "/[::1]:33333");
        assertFalse(r.isSuccess());
    }
}

