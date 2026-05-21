package com.javasleuth.core.command.server.protocol;

import com.javasleuth.core.command.CommandDescriptor;
import com.javasleuth.core.command.CommandPipeline;
import com.javasleuth.core.command.CommandProvider;
import com.javasleuth.core.command.CommandProviderContext;
import com.javasleuth.core.command.CommandRegistry;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.command.session.ClientSessionIndex;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthenticationManager.AuthenticationResult;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class CommandRequestExecutorStreamLifecycleTest {
    @Test
    public void foregroundStreamRequestReturnsAfterStartupAndCompletesOnStreamExecutor() throws Exception {
        String oldTimeout = System.getProperty("sleuth.performance.command.timeout");
        String oldTimeoutMax = System.getProperty("sleuth.performance.command.timeout.max");
        String oldAnonymousViewer = System.getProperty("sleuth.security.anonymous.viewer");
        try {
            System.setProperty("sleuth.performance.command.timeout", "50");
            System.setProperty("sleuth.performance.command.timeout.max", "50");
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            Assert.assertTrue("streaming should be enabled for this test", SleuthConfigParser.parse(config.snapshot()).protocol().isStreamingEnabled());
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CapturingReply reply = new CapturingReply();

            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authenticationManager = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm =
                    new DangerousCommandConfirmationManager(config, auditLogger);
                PerformanceOptimizer optimizer = new PerformanceOptimizer(config)
            ) {
                MetricsCollector metrics = new MetricsCollector(config);
                try {
                    StreamCommand command = blockingStream(entered, release);
                    CommandRegistry registry = registry(config, metrics, auditLogger, command);
                    CommandPipeline pipeline = new CommandPipeline(
                        new InputValidator(config, auditLogger),
                        new AuthorizationManager(config, auditLogger, authenticationManager),
                        dangerousConfirm,
                        config,
                        optimizer
                    );
                    try {
                        CommandRequestExecutor executor = new CommandRequestExecutor(
                            metrics,
                            config,
                            auditLogger,
                            authenticationManager,
                            registry,
                            pipeline,
                            new ClientSessionIndex()
                        );
                        AuthenticationResult auth = authenticationManager.createSession(AuthenticationManager.UserRole.VIEWER, "test");
                        Assert.assertTrue(auth.isSuccess());
                        ClientSession session = new ClientSession(auth.getSessionId(), "client-a", "test");
                        FutureTask<Boolean> task = new FutureTask<Boolean>(() -> executor.execute(
                            "client-a",
                            "test",
                            auth.getSessionId(),
                            "conn-a",
                            session,
                            SleuthConfigParser.parse(config.snapshot()).protocol(),
                            SleuthConfigParser.parse(config.snapshot()).security(),
                            true,
                            "watch",
                            reply,
                            "auth required",
                            "stream_write",
                            "send disconnected",
                            "close disconnected",
                            "error disconnected"
                        ));

                        Thread thread = new Thread(task, "request-stream-test");
                        thread.start();
                        if (!entered.await(1, TimeUnit.SECONDS)) {
                            Assert.fail("stream command did not start; taskDone=" + task.isDone() + ", data=" + reply.data + ", errors=" + reply.errors + ", endCount=" + reply.endCount.get());
                        }
                        Assert.assertFalse("request should return after stream startup", task.get(1, TimeUnit.SECONDS));
                        Assert.assertEquals(0, reply.endCount.get());

                        release.countDown();
                        Assert.assertTrue("stream command did not close", reply.awaitEnd(1, TimeUnit.SECONDS));
                        Assert.assertEquals(Collections.singletonList("done"), reply.data);
                        Assert.assertEquals(1, reply.endCount.get());
                        Assert.assertTrue(reply.errors.isEmpty());
                    } finally {
                        pipeline.shutdown();
                        registry.shutdown();
                    }
                } finally {
                    metrics.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.performance.command.timeout", oldTimeout);
            setOrClearProperty("sleuth.performance.command.timeout.max", oldTimeoutMax);
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnonymousViewer);
        }
    }

    private static CommandRegistry registry(
        ProductionConfig config,
        MetricsCollector metrics,
        AuditLogger auditLogger,
        StreamCommand command
    ) {
        CommandProvider provider = new CommandProvider() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
                return Collections.singletonList(
                    CommandDescriptor.of("watch", command, CommandMeta.viewer(false, true))
                );
            }
        };
        return new CommandRegistry(
            config,
            metrics,
            auditLogger,
            Collections.singletonList(provider),
            null,
            new CommandProviderContext(null, null, metrics, config, auditLogger, null, null, null, null, null, null, null)
        );
    }

    private static StreamCommand blockingStream(CountDownLatch entered, CountDownLatch release) {
        return new StreamCommand() {
            @Override
            public String execute(String[] args) {
                return "";
            }

            @Override
            public void executeStream(String[] args, StreamSink sink) throws Exception {
                entered.countDown();
                release.await(1, TimeUnit.SECONDS);
                sink.send("done");
            }

            @Override
            public String getDescription() {
                return "blocking stream";
            }
        };
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class CapturingReply implements CommandReplyChannel {
        private final List<String> data = new CopyOnWriteArrayList<String>();
        private final List<String> errors = new CopyOnWriteArrayList<String>();
        private final AtomicInteger endCount = new AtomicInteger(0);
        private final CountDownLatch ended = new CountDownLatch(1);

        @Override
        public void sendData(String data) throws IOException {
            this.data.add(data);
        }

        @Override
        public void sendError(String message) throws IOException {
            errors.add(message);
        }

        @Override
        public void end() throws IOException {
            endCount.incrementAndGet();
            ended.countDown();
        }

        private boolean awaitEnd(long timeout, TimeUnit unit) throws InterruptedException {
            return ended.await(timeout, unit);
        }
    }
}
