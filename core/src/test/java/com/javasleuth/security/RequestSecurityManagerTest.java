package com.javasleuth.security;

import org.junit.Test;

import static org.junit.Assert.*;

public class RequestSecurityManagerTest {

    @Test
    public void v2SignatureMismatchIfSidChanges() {
        // ProductionConfig reads sysprops as `sleuth.<key>`.
        System.setProperty("sleuth.security.mode", "hmac");
        System.setProperty("sleuth.security.hmac.secret", "test-secret");
        System.setProperty("sleuth.security.hmac.timestamp.window.ms", "600000");

        RequestSecurityManager mgr = RequestSecurityManager.getInstance();

        String signed = mgr.signCommandV2("help", System.currentTimeMillis(), "nonce1", "connA");
        assertTrue(signed.startsWith("SIG "));

        // Same wrapper but different sid should fail verification.
        String tampered = signed.replace(" sid=connA ", " sid=connB ");
        RequestSecurityManager.VerificationResult r = mgr.verifyAndExtract("session", tampered);
        assertFalse(r.isOk());
    }

    @Test
    public void replayDetectedWhenSameNonceAndBindingId() {
        System.setProperty("sleuth.security.mode", "hmac");
        System.setProperty("sleuth.security.hmac.secret", "test-secret");
        System.setProperty("sleuth.security.hmac.timestamp.window.ms", "600000");

        RequestSecurityManager mgr = RequestSecurityManager.getInstance();

        String signed1 = mgr.signCommandV2("help", System.currentTimeMillis(), "nonce2", "connC");
        assertTrue(mgr.verifyAndExtract("session", signed1).isOk());

        String signed2 = mgr.signCommandV2("help", System.currentTimeMillis(), "nonce2", "connC");
        assertFalse(mgr.verifyAndExtract("session", signed2).isOk());
    }
}
