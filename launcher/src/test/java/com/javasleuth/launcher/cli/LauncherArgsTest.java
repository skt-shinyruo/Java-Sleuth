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

    @Test
    public void testRestartFlagIsParsed() {
        LauncherArgs args = LauncherArgs.parse(new String[] {"--restart"});
        Assert.assertTrue(args.isRestart());
        Assert.assertEquals(LaunchMode.INTERACTIVE, args.getLaunchMode());
        Assert.assertTrue(args.validate().isEmpty());
    }

    @Test
    public void testAuthFlagsMustBePaired() {
        LauncherArgs args1 = LauncherArgs.parse(new String[] {"--auth-user", "admin"});
        Assert.assertFalse(args1.validate().isEmpty());

        LauncherArgs args2 = LauncherArgs.parse(new String[] {"--auth-pass", "secret"});
        Assert.assertFalse(args2.validate().isEmpty());

        LauncherArgs args3 = LauncherArgs.parse(new String[] {"--auth-user", "admin", "--auth-pass", "secret"});
        Assert.assertTrue(args3.validate().isEmpty());
    }
}
