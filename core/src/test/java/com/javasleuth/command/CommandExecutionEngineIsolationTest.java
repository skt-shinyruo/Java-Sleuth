package com.javasleuth.command;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.pipeline.CommandExecutionEngine;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CommandExecutionEngineIsolationTest {

    @Test
    public void streamWorkDoesNotBlockShortCommandExecutor() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("performance.command.executor.core", "1");
        config.setRuntimeConfig("performance.command.executor.max", "1");
        config.setRuntimeConfig("performance.command.executor.queue.capacity", "1");
        config.setRuntimeConfig("performance.command.stream.executor.core", "1");
        config.setRuntimeConfig("performance.command.stream.executor.max", "1");
        config.setRuntimeConfig("performance.command.stream.executor.queue.capacity", "1");

        final CommandExecutionEngine engine = new CommandExecutionEngine(config);
        final CountDownLatch streamStarted = new CountDownLatch(1);
        final CountDownLatch releaseStream = new CountDownLatch(1);
        final AtomicReference<String> streamThreadName = new AtomicReference<String>();
        final AtomicReference<String> shortThreadName = new AtomicReference<String>();
        final AtomicReference<Throwable> streamFailure = new AtomicReference<Throwable>();

        Thread streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.executeStream(new StreamCommand() {
                        @Override
                        public String execute(String[] args) {
                            return "";
                        }

                        @Override
                        public void executeStream(String[] args, StreamSink sink) throws Exception {
                            streamThreadName.set(Thread.currentThread().getName());
                            streamStarted.countDown();
                            releaseStream.await();
                        }

                        @Override
                        public String getDescription() {
                            return "blocking stream";
                        }
                    }, new String[0], CommandMeta.viewer(false, true), 5000L, new NoopStreamSink(), new CommandContext("c", "i", "s", true));
                } catch (Throwable t) {
                    streamFailure.set(t);
                }
            }
        });

        try {
            streamThread.start();
            assertTrue("stream command did not start", streamStarted.await(1, TimeUnit.SECONDS));

            String result = engine.executeSync(new Command() {
                @Override
                public String execute(String[] args) {
                    shortThreadName.set(Thread.currentThread().getName());
                    return "ok";
                }

                @Override
                public String getDescription() {
                    return "short command";
                }
            }, new String[0], CommandMeta.viewer(false, false), 300L, new CommandContext("c", "i", "s", false));

            assertEquals("ok", result);
            assertTrue(shortThreadName.get().startsWith("sleuth-cmd-short"));
            assertTrue(streamThreadName.get().startsWith("sleuth-cmd-stream"));
        } finally {
            releaseStream.countDown();
            streamThread.join(1000L);
            engine.shutdown();
        }

        assertNull(streamFailure.get());
    }

    private static final class NoopStreamSink implements StreamSink {
        @Override
        public void send(String data) {
        }

        @Override
        public void close(String summary) {
        }

        @Override
        public void error(String message) {
        }
    }
}
