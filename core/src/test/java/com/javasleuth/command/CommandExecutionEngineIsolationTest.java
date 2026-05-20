package com.javasleuth.command;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.pipeline.CommandExecutionEngine;
import com.javasleuth.core.command.pipeline.StreamExecutionHandle;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test
    public void highImpactLimiterIsScopedToExecutionEngineInstance() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("security.impact.high.concurrent.limit", "1");

        final CommandExecutionEngine first = new CommandExecutionEngine(config);
        final CommandExecutionEngine second = new CommandExecutionEngine(config);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();

        Thread firstThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    first.executeSync(blockingCommand(firstStarted, releaseFirst), new String[0], highImpactMeta(), 5000L, context());
                } catch (Throwable t) {
                    firstFailure.set(t);
                }
            }
        }, "high-impact-first-engine");

        try {
            firstThread.start();
            assertTrue("first engine command did not start", firstStarted.await(1, TimeUnit.SECONDS));

            String result = second.executeSync(new Command() {
                @Override
                public String execute(String[] args) {
                    return "second-ok";
                }

                @Override
                public String getDescription() {
                    return "second";
                }
            }, new String[0], highImpactMeta(), 1000L, context());

            assertEquals("second-ok", result);
        } finally {
            releaseFirst.countDown();
            firstThread.join(1000L);
            first.shutdown();
            second.shutdown();
        }

        assertNull(firstFailure.get());
    }

    @Test
    public void highImpactLimiterRejectsOnlyHighImpactCommandsWithinSameEngine() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("security.impact.high.concurrent.limit", "1");

        final CommandExecutionEngine engine = new CommandExecutionEngine(config);
        final CountDownLatch highStarted = new CountDownLatch(1);
        final CountDownLatch releaseHigh = new CountDownLatch(1);
        final AtomicReference<Throwable> highFailure = new AtomicReference<Throwable>();

        Thread highThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.executeSync(blockingCommand(highStarted, releaseHigh), new String[0], highImpactMeta(), 5000L, context());
                } catch (Throwable t) {
                    highFailure.set(t);
                }
            }
        }, "high-impact-same-engine");

        try {
            highThread.start();
            assertTrue("high impact command did not start", highStarted.await(1, TimeUnit.SECONDS));

            String lowResult = engine.executeSync(new Command() {
                @Override
                public String execute(String[] args) {
                    return "low-ok";
                }

                @Override
                public String getDescription() {
                    return "low";
                }
            }, new String[0], CommandMeta.viewer(false, false), 1000L, context());
            assertEquals("low-ok", lowResult);

            try {
                engine.executeSync(new Command() {
                    @Override
                    public String execute(String[] args) {
                        return "should-not-run";
                    }

                    @Override
                    public String getDescription() {
                        return "high";
                    }
                }, new String[0], highImpactMeta(), 1000L, context());
                fail("second high-impact command should be rejected");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("high impact"));
            }
        } finally {
            releaseHigh.countDown();
            highThread.join(1000L);
            engine.shutdown();
        }

        assertNull(highFailure.get());
    }

    @Test
    public void highImpactPermitIsReleasedAfterException() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("security.impact.high.concurrent.limit", "1");

        CommandExecutionEngine engine = new CommandExecutionEngine(config);
        try {
            try {
                engine.executeSync(new Command() {
                    @Override
                    public String execute(String[] args) throws Exception {
                        throw new Exception("boom");
                    }

                    @Override
                    public String getDescription() {
                        return "failing";
                    }
                }, new String[0], highImpactMeta(), 1000L, context());
                fail("failing command should throw");
            } catch (Exception expected) {
                assertEquals("boom", expected.getMessage());
            }

            String result = engine.executeSync(new Command() {
                @Override
                public String execute(String[] args) {
                    return "after-exception";
                }

                @Override
                public String getDescription() {
                    return "after";
                }
            }, new String[0], highImpactMeta(), 1000L, context());

            assertEquals("after-exception", result);
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void highImpactPermitIsReleasedAfterQueuedTimeoutCancellation() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("security.impact.high.concurrent.limit", "1");
        config.setRuntimeConfig("performance.command.executor.core", "1");
        config.setRuntimeConfig("performance.command.executor.max", "1");
        config.setRuntimeConfig("performance.command.executor.queue.capacity", "1");

        final CommandExecutionEngine engine = new CommandExecutionEngine(config);
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        final AtomicReference<Throwable> blockerFailure = new AtomicReference<Throwable>();
        final AtomicInteger timedOutCommandRuns = new AtomicInteger(0);

        Thread blockerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.executeSync(blockingCommand(blockerStarted, releaseBlocker), new String[0], CommandMeta.viewer(false, false), 5000L, context());
                } catch (Throwable t) {
                    blockerFailure.set(t);
                }
            }
        }, "short-executor-blocker");

        try {
            blockerThread.start();
            assertTrue("blocker command did not start", blockerStarted.await(1, TimeUnit.SECONDS));

            try {
                engine.executeSync(new Command() {
                    @Override
                    public String execute(String[] args) {
                        timedOutCommandRuns.incrementAndGet();
                        return "should-not-run";
                    }

                    @Override
                    public String getDescription() {
                        return "queued high impact";
                    }
                }, new String[0], highImpactMeta(), 50L, context());
                fail("queued high-impact command should time out");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("timed out"));
            }

            releaseBlocker.countDown();
            blockerThread.join(1000L);
            assertEquals(0, timedOutCommandRuns.get());
            String result = engine.executeSync(new Command() {
                @Override
                public String execute(String[] args) {
                    return "after-timeout";
                }

                @Override
                public String getDescription() {
                    return "after timeout";
                }
            }, new String[0], highImpactMeta(), 1000L, context());

            assertEquals("after-timeout", result);
        } finally {
            releaseBlocker.countDown();
            blockerThread.join(1000L);
            engine.shutdown();
        }

        assertNull(blockerFailure.get());
    }

    @Test
    public void highImpactPermitIsReleasedAfterStreamCancelCompletes() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("security.impact.high.concurrent.limit", "1");
        config.setRuntimeConfig("performance.command.stream.executor.core", "1");
        config.setRuntimeConfig("performance.command.stream.executor.max", "1");

        CommandExecutionEngine engine = new CommandExecutionEngine(config);
        try {
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch observedCancellation = new CountDownLatch(1);

            StreamExecutionHandle handle = engine.executeStream(new StreamCommand() {
                @Override
                public String execute(String[] args) {
                    return "";
                }

                @Override
                public void executeStream(String[] args, StreamSink sink) {
                    started.countDown();
                    while (!CommandContextHolder.get().getCancellationToken().isCancelled()) {
                        try {
                            Thread.sleep(20L);
                        } catch (InterruptedException e) {
                            if (CommandContextHolder.get().getCancellationToken().isCancelled()) {
                                break;
                            }
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    observedCancellation.countDown();
                }

                @Override
                public String getDescription() {
                    return "cancellable high impact stream";
                }
            }, new String[0], highImpactMeta(), 1000L, new NoopStreamSink(), new CommandContext("c", "i", "s", true));

            assertTrue("stream command did not start", started.await(1, TimeUnit.SECONDS));
            handle.cancel("test");
            assertTrue("stream command did not observe cancellation", observedCancellation.await(1, TimeUnit.SECONDS));
            assertTrue("stream completion should be cancelled", !handle.awaitCompletion().isSuccess());

            String result = engine.executeSync(new Command() {
                @Override
                public String execute(String[] args) {
                    return "after-cancel";
                }

                @Override
                public String getDescription() {
                    return "after cancel";
                }
            }, new String[0], highImpactMeta(), 1000L, context());

            assertEquals("after-cancel", result);
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void highImpactRuntimeLimitIncreaseUsesFreshEngineScopedCapacity() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("security.impact.high.concurrent.limit", "1");

        final CommandExecutionEngine engine = new CommandExecutionEngine(config);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();

        Thread firstThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.executeSync(blockingCommand(firstStarted, releaseFirst), new String[0], highImpactMeta(), 5000L, context());
                } catch (Throwable t) {
                    firstFailure.set(t);
                }
            }
        }, "high-impact-before-config-change");

        try {
            firstThread.start();
            assertTrue("first command did not start", firstStarted.await(1, TimeUnit.SECONDS));
            config.setRuntimeConfig("security.impact.high.concurrent.limit", "2");

            String result = engine.executeSync(new Command() {
                @Override
                public String execute(String[] args) {
                    return "limit-two";
                }

                @Override
                public String getDescription() {
                    return "after config change";
                }
            }, new String[0], highImpactMeta(), 1000L, context());

            assertEquals("limit-two", result);
        } finally {
            releaseFirst.countDown();
            firstThread.join(1000L);
            engine.shutdown();
        }

        assertNull(firstFailure.get());
    }

    @Test
    public void highImpactRuntimeLimitDecreaseKeepsInFlightCommandCounted() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("security.impact.high.concurrent.limit", "2");

        final CommandExecutionEngine engine = new CommandExecutionEngine(config);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();

        Thread firstThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.executeSync(blockingCommand(firstStarted, releaseFirst), new String[0], highImpactMeta(), 5000L, context());
                } catch (Throwable t) {
                    firstFailure.set(t);
                }
            }
        }, "high-impact-before-limit-decrease");

        try {
            firstThread.start();
            assertTrue("first command did not start", firstStarted.await(1, TimeUnit.SECONDS));
            config.setRuntimeConfig("security.impact.high.concurrent.limit", "1");

            try {
                engine.executeSync(new Command() {
                    @Override
                    public String execute(String[] args) {
                        return "should-not-run";
                    }

                    @Override
                    public String getDescription() {
                        return "after config decrease";
                    }
                }, new String[0], highImpactMeta(), 1000L, context());
                fail("new high-impact command should respect lowered limit");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("high impact"));
            }
        } finally {
            releaseFirst.countDown();
            firstThread.join(1000L);
            engine.shutdown();
        }

        assertNull(firstFailure.get());
    }

    private static Command blockingCommand(final CountDownLatch started, final CountDownLatch release) {
        return new Command() {
            @Override
            public String execute(String[] args) throws Exception {
                started.countDown();
                assertTrue("test should release blocking command", release.await(5, TimeUnit.SECONDS));
                return "ok";
            }

            @Override
            public String getDescription() {
                return "blocking";
            }
        };
    }

    private static CommandContext context() {
        return new CommandContext("c", "i", "s", false);
    }

    private static CommandMeta highImpactMeta() {
        return CommandMeta.viewer(false, false).withImpact(CommandMeta.ImpactLevel.HIGH);
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
