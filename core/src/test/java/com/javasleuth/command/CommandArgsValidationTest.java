package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.MonitorCommand;
import com.javasleuth.core.command.impl.VmToolCommand;
import com.javasleuth.core.command.impl.WatchCommand;
import com.javasleuth.core.command.spec.CommandSpecParseException;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.bootstrap.spy.SleuthSpyAPI;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.Locale;
import org.junit.Assert;
import org.junit.Test;

public class CommandArgsValidationTest {

    public static class VmToolBase {
    }

    public static class VmToolChildOne extends VmToolBase {
    }

    public static class VmToolChildTwo extends VmToolBase {
    }

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

    @Test
    public void watchRejectsInvalidCount() {
        try {
            CommandSpecParser.parse(WatchCommand.spec(), new String[] {"watch", "A", "m", "-n", "abc"});
            Assert.fail("expected invalid integer");
        } catch (CommandSpecParseException expected) {
            Assert.assertEquals("E_ARGS_INVALID", expected.getCode());
        }
    }

    @Test
    public void monitorRejectsZeroInterval() {
        try {
            CommandSpecParser.parse(MonitorCommand.spec(), new String[] {"monitor", "A", "m", "-i", "0"});
            Assert.fail("expected range error");
        } catch (CommandSpecParseException expected) {
            Assert.assertEquals("E_ARGS_RANGE", expected.getCode());
        }
    }

    @Test
    public void vmtoolInstancesRejectsInvalidLimit() {
        try {
            CommandSpecParser.parse(VmToolCommand.spec(), new String[] {"vmtool", "instances", "track-1", "--limit", "abc"});
            Assert.fail("expected invalid integer");
        } catch (CommandSpecParseException expected) {
            Assert.assertEquals("E_ARGS_INVALID", expected.getCode());
        }
    }

    @Test
    public void vmtoolTrackUsesRuntimeConfigWhenLimitsAreOmitted() throws Exception {
        SleuthSpyAPI.destroy();

        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("vmtool.track.max.entries", "123");
        config.setRuntimeConfig("vmtool.track.class.limit", "1");
        AuditLogger auditLogger = new AuditLogger(config);
        DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger);
        Instrumentation instrumentation = fakeInstrumentationWithLoadedClasses(
            VmToolChildOne.class,
            VmToolChildTwo.class,
            VmToolBase.class
        );
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        VmToolSessionRegistry registry = new VmToolSessionRegistry(dispatcher);
        SleuthSpyAPI.setSpy(dispatcher);
        SleuthSpyAPI.init();

        try {
            String result = new VmToolCommand(
                instrumentation,
                transformer,
                config,
                dangerousConfirm,
                registry
            ).execute(new String[] {"vmtool", "track", VmToolBase.class.getName(), "--subclasses"});

            Assert.assertTrue(result, result.contains("vmtool track started"));
            VmToolSessionRegistry.TrackSession session = registry.listSessions().values().iterator().next();
            Assert.assertEquals(123, session.getMaxEntries());
            Assert.assertEquals(2, session.getEnhancedClasses().size());
        } finally {
            registry.shutdown(instrumentation, transformer, "test_cleanup");
            dangerousConfirm.close();
            auditLogger.close();
            SleuthSpyAPI.destroy();
        }
    }

    private static Instrumentation fakeInstrumentationWithLoadedClasses(Class<?>... loadedClasses) {
        Class<?>[] snapshot = loadedClasses.clone();
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, args) -> {
                if ("getAllLoadedClasses".equals(method.getName())) {
                    return snapshot;
                }
                if ("isModifiableClass".equals(method.getName())) {
                    return true;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return false;
                }
                if (returnType == Integer.TYPE) {
                    return 0;
                }
                if (returnType == Long.TYPE) {
                    return 0L;
                }
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                return null;
            });
    }
}
