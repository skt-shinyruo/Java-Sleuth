package com.javasleuth.core.command;

import com.javasleuth.bootstrap.monitor.MonitorInterceptor;
import com.javasleuth.bootstrap.monitor.StackInterceptor;
import com.javasleuth.bootstrap.monitor.TraceInterceptor;
import com.javasleuth.bootstrap.monitor.TtInterceptor;
import com.javasleuth.bootstrap.monitor.VmToolInterceptor;
import com.javasleuth.bootstrap.monitor.WatchInterceptor;
import com.javasleuth.bootstrap.spy.SleuthSpyAPI;
import com.javasleuth.core.command.impl.MonitorCommand;
import com.javasleuth.core.command.impl.StackCommand;
import com.javasleuth.core.command.impl.TraceCommand;
import com.javasleuth.core.command.impl.TtCommand;
import com.javasleuth.core.command.impl.WatchCommand;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import org.junit.Assert;
import org.junit.Test;

public class ListenerModeRequirementTest {

    public static class DummyTarget {
        public String doWork(String input) {
            return input;
        }
    }

    @Test
    public void commands_requireInstalledDispatcher_andDoNotRegisterLegacyFallbacks() throws Exception {
        resetLegacyState();

        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        JobManager jobManager = new JobManager();
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        Instrumentation inst = fakeInstrumentationWithLoadedClasses(DummyTarget.class);

        String watch = new WatchCommand(inst, transformer, config, jobManager, dispatcher)
            .execute(new String[]{"watch", DummyTarget.class.getName(), "doWork", "-t", "1"});
        String trace = new TraceCommand(inst, transformer, config, jobManager, dispatcher)
            .execute(new String[]{"trace", DummyTarget.class.getName(), "doWork", "-t", "1"});
        String monitor = new MonitorCommand(inst, transformer, jobManager, dispatcher)
            .execute(new String[]{"monitor", DummyTarget.class.getName(), "doWork", "-n", "1", "-i", "1"});
        String stack = new StackCommand(inst, transformer, config, jobManager, dispatcher)
            .execute(new String[]{"stack", DummyTarget.class.getName(), "doWork", "-n", "1", "-t", "1"});
        String tt = new TtCommand(inst, transformer, config, jobManager, dispatcher)
            .execute(new String[]{"tt", DummyTarget.class.getName(), "doWork", "-t", "1"});

        Assert.assertTrue(watch.contains("SleuthSpyDispatcher is not installed"));
        Assert.assertTrue(trace.contains("SleuthSpyDispatcher is not installed"));
        Assert.assertTrue(monitor.contains("SleuthSpyDispatcher is not installed"));
        Assert.assertTrue(stack.contains("SleuthSpyDispatcher is not installed"));
        Assert.assertTrue(tt.contains("SleuthSpyDispatcher is not installed"));

        Assert.assertEquals(0, WatchInterceptor.getActiveWatchCount());
        Assert.assertEquals(0, TraceInterceptor.getActiveTraceCount());
        Assert.assertEquals(0, MonitorInterceptor.getActiveMonitorCount());
        Assert.assertEquals(0, StackInterceptor.getActiveStackCount());
        Assert.assertEquals(0, TtInterceptor.getActiveTtCount());
    }

    @Test
    public void streamCommands_reparseWhenBackgroundFlagRemovedFromRawArgs() throws Exception {
        resetLegacyState();

        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        JobManager jobManager = new JobManager();
        jobManager.configureExecution(1, 1);
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        Instrumentation inst = fakeInstrumentationWithLoadedClasses(DummyTarget.class);
        try {
            assertStaleBackgroundContextDoesNotStartJob(
                new WatchCommand(inst, transformer, config, jobManager, dispatcher),
                WatchCommand.spec(),
                new String[] {"watch", DummyTarget.class.getName(), "doWork", "-t", "1", "--bg"},
                new String[] {"watch", DummyTarget.class.getName(), "doWork", "-t", "1"},
                "watch"
            );
            assertStaleBackgroundContextDoesNotStartJob(
                new TraceCommand(inst, transformer, config, jobManager, dispatcher),
                TraceCommand.spec(),
                new String[] {"trace", DummyTarget.class.getName(), "doWork", "-t", "1", "--bg"},
                new String[] {"trace", DummyTarget.class.getName(), "doWork", "-t", "1"},
                "trace"
            );
            assertStaleBackgroundContextDoesNotStartJob(
                new MonitorCommand(inst, transformer, jobManager, dispatcher),
                MonitorCommand.spec(),
                new String[] {"monitor", DummyTarget.class.getName(), "doWork", "-n", "1", "-i", "1", "--bg"},
                new String[] {"monitor", DummyTarget.class.getName(), "doWork", "-n", "1", "-i", "1"},
                "monitor"
            );
        } finally {
            CommandContextHolder.clear();
            jobManager.shutdown("test cleanup");
        }
    }

    @Test
    public void vmtoolTrack_requiresInstalledDispatcher_andDoesNotRegisterLegacyFallback() {
        resetLegacyState();

        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
        VmToolSessionRegistry registry = new VmToolSessionRegistry(dispatcher);
        Instrumentation inst = fakeInstrumentationWithLoadedClasses(DummyTarget.class);

        VmToolSessionRegistry.StartResult result = registry.startTrack(
            inst,
            transformer,
            DummyTarget.class.getName(),
            null,
            false,
            false,
            10,
            10
        );

        Assert.assertFalse(result.isOk());
        Assert.assertTrue(result.getMessage().contains("SleuthSpyDispatcher is not installed"));
        Assert.assertTrue(VmToolInterceptor.listTrackStats().isEmpty());
        Assert.assertTrue(registry.listSessions().isEmpty());
    }

    private static void resetLegacyState() {
        WatchInterceptor.unregisterAllWatches();
        TraceInterceptor.unregisterAllTraces();
        MonitorInterceptor.unregisterAllMonitors();
        StackInterceptor.unregisterAll();
        TtInterceptor.unregisterAll();
        TtInterceptor.clear();
        VmToolInterceptor.clearAll();
        SleuthSpyAPI.destroy();
    }

    private static void assertStaleBackgroundContextDoesNotStartJob(
        Command command,
        CommandSpec spec,
        String[] capturedArgs,
        String[] currentArgs,
        String commandName
    ) throws Exception {
        CommandContext context = new CommandContext("c", "i", "s", true)
            .withParsedCommand(CommandSpecParser.parse(spec, capturedArgs));
        CommandContextHolder.set(context);
        try {
            String output = command.execute(currentArgs);

            Assert.assertTrue(output, output.contains("SleuthSpyDispatcher is not installed"));
            Assert.assertFalse(output, output.contains("Started " + commandName + " in background"));
        } finally {
            CommandContextHolder.clear();
        }
    }

    private static Instrumentation fakeInstrumentationWithLoadedClasses(Class<?>... loadedClasses) {
        Class<?>[] snapshot = loadedClasses.clone();
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                if ("getAllLoadedClasses".equals(method.getName())) {
                    return snapshot;
                }
                if ("isModifiableClass".equals(method.getName())) {
                    return true;
                }
                if ("removeTransformer".equals(method.getName())) {
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
            }
        );
    }
}
