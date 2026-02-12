package com.javasleuth.command.protocol;

import java.util.Locale;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class KvLineCodecTest {

    @Test
    public void parseAfterVerb_nullOrEmpty_returnsEmptyMap() {
        Assert.assertTrue(KvLineCodec.parseAfterVerb(null).isEmpty());
        Assert.assertTrue(KvLineCodec.parseAfterVerb("").isEmpty());
        Assert.assertTrue(KvLineCodec.parseAfterVerb("   ").isEmpty());
    }

    @Test
    public void parseAfterVerb_onlyVerb_returnsEmptyMap() {
        Assert.assertTrue(KvLineCodec.parseAfterVerb("HELLO").isEmpty());
        Assert.assertTrue(KvLineCodec.parseAfterVerb("CONFIG\t\n").isEmpty());
    }

    @Test
    public void parseAfterVerb_parsesTokensAfterVerb_andLowercasesKeys() {
        Map<String, String> kv = KvLineCodec.parseAfterVerb("CONFIG V=1 protocol=binary connId=XYZ maxPayload=123");
        Assert.assertEquals("1", kv.get("v"));
        Assert.assertEquals("binary", kv.get("protocol"));
        Assert.assertEquals("XYZ", kv.get("connid"));
        Assert.assertEquals("123", kv.get("maxpayload"));
    }

    @Test
    public void parseAfterVerb_ignoresInvalidTokens() {
        Map<String, String> kv = KvLineCodec.parseAfterVerb("SIG ts=1 nonce=abc =bad foo= bar=baz ok=1");
        Assert.assertEquals("1", kv.get("ts"));
        Assert.assertEquals("abc", kv.get("nonce"));
        Assert.assertEquals("baz", kv.get("bar"));
        Assert.assertEquals("1", kv.get("ok"));
        Assert.assertFalse(kv.containsKey("foo"));
    }

    @Test
    public void parseAfterVerb_lowercaseIsLocaleIndependent() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            Map<String, String> kv = KvLineCodec.parseAfterVerb("CONFIG connId=XYZ");
            Assert.assertEquals("XYZ", kv.get("connid"));
        } finally {
            Locale.setDefault(old);
        }
    }
}
