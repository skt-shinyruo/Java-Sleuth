package com.javasleuth.core.command;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
}
