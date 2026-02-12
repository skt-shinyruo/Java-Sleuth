package com.javasleuth.launcher.client;

import org.junit.Assert;
import org.junit.Test;

public class ConnectHostResolverTest {

    @Test
    public void testResolveNullAndEmpty() {
        Assert.assertEquals("127.0.0.1", ConnectHostResolver.resolveConnectHost(null));
        Assert.assertEquals("127.0.0.1", ConnectHostResolver.resolveConnectHost(""));
        Assert.assertEquals("127.0.0.1", ConnectHostResolver.resolveConnectHost("   "));
    }

    @Test
    public void testResolveUnspecified() {
        Assert.assertEquals("127.0.0.1", ConnectHostResolver.resolveConnectHost("0.0.0.0"));
        Assert.assertEquals("127.0.0.1", ConnectHostResolver.resolveConnectHost("::"));
        Assert.assertEquals("127.0.0.1", ConnectHostResolver.resolveConnectHost("0:0:0:0:0:0:0:0"));
    }

    @Test
    public void testResolveKeepsRealHost() {
        Assert.assertEquals("127.0.0.1", ConnectHostResolver.resolveConnectHost("127.0.0.1"));
        Assert.assertEquals("localhost", ConnectHostResolver.resolveConnectHost("localhost"));
        Assert.assertEquals("192.168.1.10", ConnectHostResolver.resolveConnectHost("192.168.1.10"));
    }
}

