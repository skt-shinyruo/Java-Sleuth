package com.javasleuth.launcher.cli;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class LauncherArgsTest {

    @Test
    public void testDefaultIsInteractive() {
        LauncherArgs args = LauncherArgs.parse(new String[0]);
        Assert.assertEquals(LaunchMode.INTERACTIVE, args.getLaunchMode());
        Assert.assertTrue(args.validate().isEmpty());
    }

    @Test
    public void testHelp() {
        LauncherArgs args = LauncherArgs.parse(new String[] {"--help"});
        Assert.assertTrue(args.isHelp());
    }

    @Test
    public void testHeadlessRequiresPidAndCmdOrScript() {
        LauncherArgs args = LauncherArgs.parse(new String[] {"--cmd", "version"});
        Assert.assertEquals(LaunchMode.HEADLESS, args.getLaunchMode());
        List<String> errors = args.validate();
        Assert.assertFalse(errors.isEmpty());
        Assert.assertTrue(errors.toString().contains("--pid"));
    }

    @Test
    public void testHeadlessWithPidAndCmdIsOk() {
        LauncherArgs args = LauncherArgs.parse(new String[] {"--pid", "123", "--cmd", "version"});
        Assert.assertEquals(LaunchMode.HEADLESS, args.getLaunchMode());
        Assert.assertTrue(args.validate().isEmpty());
    }

    @Test
    public void testUnknownArgIsRejected() {
        LauncherArgs args = LauncherArgs.parse(new String[] {"--pid", "123", "--cmd", "version", "--insecure"});
        List<String> errors = args.validate();
        Assert.assertFalse(errors.isEmpty());
        Assert.assertTrue(errors.toString().contains("--insecure"));
    }
}
