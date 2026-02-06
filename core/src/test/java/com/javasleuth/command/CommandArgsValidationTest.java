package com.javasleuth.command;

import com.javasleuth.security.AuthenticationManager.UserRole;
import java.util.Locale;
import org.junit.Assert;
import org.junit.Test;

public class CommandArgsValidationTest {

    @Test
    public void getInt_returnsDefaultWhenMissing() {
        int v = CommandArgs.getInt(
            new String[]{"stack", "monitor", "start"},
            3,
            "intervalMs",
            1000,
            10,
            600_000,
            "stack monitor start [intervalMs]"
        );
        Assert.assertEquals(1000, v);
    }

    @Test
    public void getInt_throwsInvalidOnBadNumber() {
        try {
            CommandArgs.getInt(
                new String[]{"stack", "monitor", "start", "abc"},
                3,
                "intervalMs",
                1000,
                10,
                600_000,
                "stack monitor start [intervalMs]"
            );
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().startsWith(CommandArgs.E_ARGS_INVALID + ":"));
            Assert.assertTrue(e.getMessage().contains("intervalMs"));
            Assert.assertTrue(e.getMessage().contains("stack monitor start"));
        }
    }

    @Test
    public void getInt_throwsRangeOnTooSmall() {
        try {
            CommandArgs.getInt(
                new String[]{"stack", "monitor", "start", "5"},
                3,
                "intervalMs",
                1000,
                10,
                600_000,
                "stack monitor start [intervalMs]"
            );
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().startsWith(CommandArgs.E_ARGS_RANGE + ":"));
        }
    }

    @Test
    public void requireLong_throwsMissingWhenAbsent() {
        try {
            CommandArgs.requireLong(
                new String[]{"stack", "dump"},
                2,
                "threadId",
                1L,
                Long.MAX_VALUE,
                "stack dump [thread-id]"
            );
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().startsWith(CommandArgs.E_ARGS_MISSING + ":"));
        }
    }

    @Test
    public void requireLong_throwsRangeOnNegative() {
        try {
            CommandArgs.requireLong(
                new String[]{"stack", "dump", "-1"},
                2,
                "threadId",
                1L,
                Long.MAX_VALUE,
                "stack dump [thread-id]"
            );
            Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().startsWith(CommandArgs.E_ARGS_RANGE + ":"));
        }
    }

    @Test
    public void commandMeta_subcommandRoleLookup_isLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            CommandMeta meta = CommandMeta.viewer(true, true).withSubcommandRole("info", UserRole.ADMIN);
            Assert.assertEquals(UserRole.ADMIN, meta.getRequiredRoleForArgs(new String[]{"mbean", "INFO"}));
        } finally {
            Locale.setDefault(original);
        }
    }
}

