package com.javasleuth.core.command;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class JobManagerConcurrencyTest {

    @Test
    public void submitStreamJob_isBoundedByMaxRunningAndQueueCapacity() throws Exception {
        JobManager jm = new JobManager();

        // Ensure a small bound for deterministic rejection: 1 running + 1 queued.
        jm.configureExecution(1, 1);

        CountDownLatch block = new CountDownLatch(1);

        String id1 = jm.submitStreamJob("t1", "cmd1", sink -> {
            sink.send("started1");
            block.await(2, TimeUnit.SECONDS);
            sink.close("done1");
        });
        assertNotNull(id1);

        String id2 = jm.submitStreamJob("t2", "cmd2", sink -> {
            sink.send("started2");
            block.await(2, TimeUnit.SECONDS);
            sink.close("done2");
        });
        assertNotNull(id2);

        try {
            jm.submitStreamJob("t3", "cmd3", sink -> sink.send("started3"));
            fail("Expected RejectedExecutionException");
        } catch (RejectedExecutionException e) {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage().toLowerCase().contains("maxrunning"));
        } finally {
            block.countDown();
            // Best-effort cleanup (avoid leaking background jobs across test suite).
            jm.shutdown("test_teardown");
        }
    }

    @Test
    public void stop_cancelsContextTokenVisibleToJob() throws Exception {
        JobManager jm = new JobManager();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        AtomicBoolean observedCancellation = new AtomicBoolean(false);

        CommandContextHolder.set(new CommandContext("client", "test", "session", false));
        try {
            String id = jm.submitStreamJob("watch", "watch", sink -> {
                started.countDown();
                while (!CommandContextHolder.get().getCancellationToken().isCancelled()) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        if (CommandContextHolder.get().getCancellationToken().isCancelled()) {
                            observedCancellation.set(true);
                            observed.countDown();
                        }
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                observedCancellation.set(true);
                observed.countDown();
            });

            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(jm.stop(id));

            assertTrue(observed.await(1, TimeUnit.SECONDS));
            assertTrue(observedCancellation.get());
        } finally {
            CommandContextHolder.clear();
            jm.shutdown("test_teardown");
        }
    }
}
