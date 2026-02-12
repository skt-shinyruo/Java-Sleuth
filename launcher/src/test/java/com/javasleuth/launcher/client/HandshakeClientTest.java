package com.javasleuth.launcher.client;

import com.javasleuth.command.protocol.KvLineCodec;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class HandshakeClientTest {

    @Test
    public void testBuildHelloLineDefaultsToFramed() {
        String line = HandshakeClient.buildHelloLine("invalid", "abc");
        Assert.assertTrue(line.startsWith("HELLO"));
        Assert.assertTrue(line.contains("protocol=framed"));
        Assert.assertTrue(line.contains("connId=abc"));
    }

    @Test
    public void testParseHandshakeKvLowercasesKeys() {
        Map<String, String> kv = KvLineCodec.parseAfterVerb("CONFIG v=1 protocol=binary connId=XYZ maxPayload=123");
        Assert.assertEquals("binary", kv.get("protocol"));
        Assert.assertEquals("XYZ", kv.get("connid"));
        Assert.assertEquals("123", kv.get("maxpayload"));
    }

    @Test
    public void testParseConfigLine() {
        HandshakeConfig cfg = HandshakeClient.parseConfigLine("CONFIG v=1 protocol=binary streaming=true maxpayload=4096 connId=xyz");
        Assert.assertNotNull(cfg);
        Assert.assertEquals("binary", cfg.getProtocol());
        Assert.assertTrue(cfg.isStreamingEnabled());
        Assert.assertEquals(Integer.valueOf(4096), cfg.getMaxPayloadBytes());
        Assert.assertEquals("xyz", cfg.getConnId());
    }
}

