package com.javasleuth.launcher.shell;

import org.junit.Assert;
import org.junit.Test;

public class DefaultStreamPolicyTest {

    @Test
    public void testStreamingCommands() {
        DefaultStreamPolicy p = new DefaultStreamPolicy();
        Assert.assertTrue(p.isStreamingCommand("watch com.Foo bar"));
        Assert.assertTrue(p.isStreamingCommand("TRACE com.Foo bar"));
        Assert.assertTrue(p.isStreamingCommand("monitor"));
        Assert.assertTrue(p.isStreamingCommand("tt -l"));
        Assert.assertTrue(p.isStreamingCommand("stack com.Foo bar"));
    }

    @Test
    public void testNonStreamingCommands() {
        DefaultStreamPolicy p = new DefaultStreamPolicy();
        Assert.assertFalse(p.isStreamingCommand("help"));
        Assert.assertFalse(p.isStreamingCommand("version"));
        Assert.assertFalse(p.isStreamingCommand(""));
    }
}

