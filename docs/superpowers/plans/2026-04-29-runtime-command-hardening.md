# Runtime Command Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden command execution, runtime config, cross-ClassLoader startup, and command parsing according to `docs/superpowers/specs/2026-04-29-runtime-command-hardening-design.md`.

**Architecture:** Keep the existing command/plugin APIs stable while adding internal runtime primitives. Split command execution into short and stream pools, expose cancellation through `CommandContext`, validate runtime config through schema keys, add a bootstrap contract handshake, then migrate high-risk commands to a lightweight `CommandSpec` parser and help renderer.

**Tech Stack:** Java 8, Maven, JUnit 4, existing Java-Sleuth `core`, `foundation`, `bootstrap`, and `agent` modules.

---

## Scope Note

The design spans four independent subsystems, but the approved direction is one total plan. Each task below is phase-isolated and has its own tests so workers can land the work in small commits.

## File Structure

Execution and cancellation:

- Create `core/src/main/java/com/javasleuth/core/command/CancellationToken.java` for the read-only cancellation contract.
- Create `core/src/main/java/com/javasleuth/core/command/CancellationTokenSource.java` for owned cancellation state.
- Modify `core/src/main/java/com/javasleuth/core/command/CommandContext.java` to carry a token and parsed command context.
- Modify `core/src/main/java/com/javasleuth/core/command/pipeline/CommandExecutionEngine.java` to split short and stream pools.
- Modify `core/src/main/java/com/javasleuth/core/command/JobManager.java` to use the same token model for background jobs.
- Modify long-running command loops in `WatchCommand`, `TraceCommand`, `MonitorCommand`, `TtRecordEngine`, and `StackTraceLiteEngine` to observe cancellation.

Config schema:

- Create `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigValidationResult.java` for runtime config validation results.
- Create `foundation/src/main/java/com/javasleuth/foundation/config/model/VmToolConfig.java` for typed vmtool defaults.
- Modify `ConfigKey`, `SleuthConfigSchema`, `ProductionConfig`, `SleuthConfig`, `SleuthConfigParser`, `PerformanceConfig`, `StatusCommand`, `ConfigCommand`, `SleuthClassFileTransformer`, and command implementations that currently read raw keys.

Cross-ClassLoader contract:

- Modify `bootstrap/src/main/java/com/javasleuth/bootstrap/agent/AgentLifecycle.java` to expose `contractVersion()`.
- Modify `agent/src/main/java/com/javasleuth/agent/CrossClassLoaderFacade.java` to return explicit diagnostics.
- Modify `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java` to abort early on incompatible contracts.
- Modify `core/src/main/java/com/javasleuth/core/agent/runtime/BootstrapBridge.java` and `StatusCommand` to surface contract status.
- Modify `docs/dev/cross-classloader-contract.md` to document the new contract item.

CommandSpec:

- Create `core/src/main/java/com/javasleuth/core/command/SpecBackedCommand.java` as an optional marker for migrated commands.
- Create `core/src/main/java/com/javasleuth/core/command/spec/CommandSpec.java`, `SubcommandSpec.java`, `ArgumentSpec.java`, `OptionSpec.java`, `ParsedCommand.java`, `CommandSpecParser.java`, `CommandHelpRenderer.java`, and `CommandSpecParseException.java`.
- Modify `CommandDescriptor`, `CommandRegistry`, `CommandPipeline`, `BuiltinCommandProvider`, `HelpCommand`, `WatchCommand`, `TraceCommand`, `MonitorCommand`, and `VmToolCommand`.

## Task 1: Add Stream Executor Configuration and Isolation Test

**Files:**
- Create: `core/src/test/java/com/javasleuth/command/CommandExecutionEngineIsolationTest.java`
- Modify later: `foundation/src/main/java/com/javasleuth/foundation/config/schema/SleuthConfigSchema.java`
- Modify later: `foundation/src/main/java/com/javasleuth/foundation/config/model/PerformanceConfig.java`
- Modify later: `foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfigParser.java`
- Modify later: `core/src/main/java/com/javasleuth/core/command/pipeline/CommandExecutionEngine.java`

- [ ] **Step 1: Write the failing executor isolation test**

Create `core/src/test/java/com/javasleuth/command/CommandExecutionEngineIsolationTest.java` with this content:

```java
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
import org.junit.Assert;
import org.junit.Test;

public class CommandExecutionEngineIsolationTest {
    @Test
    public void streamWorkDoesNotBlockShortCommands() throws Exception {
        ProductionConfig config = new ProductionConfig();
        config.setRuntimeConfig("performance.command.executor.core", "1");
        config.setRuntimeConfig("performance.command.executor.max", "1");
        config.setRuntimeConfig("performance.command.executor.queue.capacity", "1");
        config.setRuntimeConfig("performance.command.stream.executor.core", "1");
        config.setRuntimeConfig("performance.command.stream.executor.max", "1");
        config.setRuntimeConfig("performance.command.stream.executor.queue.capacity", "1");

        final CommandExecutionEngine engine = new CommandExecutionEngine(config);
        final CountDownLatch streamStarted = new CountDownLatch(1);
        final CountDownLatch releaseStream = new CountDownLatch(1);
        final AtomicReference<Exception> streamError = new AtomicReference<Exception>();

        Thread streamCaller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    engine.executeStream(blockingStream(streamStarted, releaseStream), new String[] {"watch"},
                        CommandMeta.operator(false, true), 5000L, noOpSink(), context(true));
                } catch (Exception e) {
                    streamError.set(e);
                }
            }
        }, "test-stream-caller");

        streamCaller.start();
        Assert.assertTrue("stream task did not start", streamStarted.await(2, TimeUnit.SECONDS));

        String output = engine.executeSync(okCommand(), new String[] {"status"},
            CommandMeta.viewer(false, false), 300L, context(false));

        Assert.assertEquals("ok", output);
        releaseStream.countDown();
        streamCaller.join(2000L);
        engine.shutdown();
        Assert.assertNull(streamError.get());
    }

    private static CommandContext context(boolean streaming) {
        return new CommandContext("client", "test", "session", streaming);
    }

    private static Command okCommand() {
        return new Command() {
            @Override
            public String execute(String[] args) {
                return "ok";
            }

            @Override
            public String getDescription() {
                return "ok";
            }
        };
    }

    private static StreamCommand blockingStream(final CountDownLatch started, final CountDownLatch release) {
        return new StreamCommand() {
            @Override
            public void executeStream(String[] args, StreamSink sink) throws Exception {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
            }

            @Override
            public String execute(String[] args) {
                return "";
            }

            @Override
            public String getDescription() {
                return "blocking";
            }
        };
    }

    private static StreamSink noOpSink() {
        return new StreamSink() {
            @Override
            public void send(String chunk) {
            }

            @Override
            public void error(String message) {
            }

            @Override
            public void close(String reason) {
            }
        };
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
mvn -pl core -am -Dtest=CommandExecutionEngineIsolationTest test
```

Expected: FAIL. Before implementation, the stream-specific config keys do not exist and sync and stream work share the same executor, so the short command should time out or the test should fail during config parsing after Task 5 strict config is in place.

- [ ] **Step 3: Add stream executor config keys and model fields**

In `SleuthConfigSchema`, add these keys after `PERFORMANCE_COMMAND_EXECUTOR_QUEUE_CAPACITY`:

```java
public static final ConfigKey<Integer> PERFORMANCE_COMMAND_STREAM_EXECUTOR_CORE = register(
    ConfigKey.intKey("performance.command.stream.executor.core")
        .defaultValue(2)
        .longRange(1, 64)
        .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
        .build()
);

public static final ConfigKey<Integer> PERFORMANCE_COMMAND_STREAM_EXECUTOR_MAX = register(
    ConfigKey.intKey("performance.command.stream.executor.max")
        .defaultValue(4)
        .longRange(1, 64)
        .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
        .build()
);

public static final ConfigKey<Integer> PERFORMANCE_COMMAND_STREAM_EXECUTOR_QUEUE_CAPACITY = register(
    ConfigKey.intKey("performance.command.stream.executor.queue.capacity")
        .defaultValue(32)
        .longRange(1, 10000)
        .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
        .build()
);
```

In `PerformanceConfig`, add constructor fields and getters:

```java
private final int commandStreamExecutorCoreSize;
private final int commandStreamExecutorMaxSize;
private final int commandStreamExecutorQueueCapacity;

public int getCommandStreamExecutorCoreSize() {
    return commandStreamExecutorCoreSize;
}

public int getCommandStreamExecutorMaxSize() {
    return commandStreamExecutorMaxSize;
}

public int getCommandStreamExecutorQueueCapacity() {
    return commandStreamExecutorQueueCapacity;
}
```

In `SleuthConfigParser.parsePerformance(...)`, read and normalize the stream executor values:

```java
int streamExecCore = SleuthConfigSchema.PERFORMANCE_COMMAND_STREAM_EXECUTOR_CORE.read(config);
int streamExecMax = SleuthConfigSchema.PERFORMANCE_COMMAND_STREAM_EXECUTOR_MAX.read(config);
if (streamExecMax < streamExecCore) {
    SleuthLogger.warn("Config normalized: performance.command.stream.executor.max < core, auto-adjusted to core");
    streamExecMax = streamExecCore;
}
int streamExecQueueCapacity = SleuthConfigSchema.PERFORMANCE_COMMAND_STREAM_EXECUTOR_QUEUE_CAPACITY.read(config);
```

Pass the three new values into the `PerformanceConfig` constructor immediately after the existing command executor queue capacity.

- [ ] **Step 4: Split executors in `CommandExecutionEngine`**

Change fields:

```java
private final ThreadPoolExecutor shortCommandExecutor;
private final ThreadPoolExecutor streamCommandExecutor;
```

Add a helper in `CommandExecutionEngine`:

```java
private static ThreadPoolExecutor newExecutor(String threadName, int coreSize, int maxSize, int queueCapacity) {
    BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>(queueCapacity);
    ThreadPoolExecutor tpe = new ThreadPoolExecutor(
        coreSize,
        Math.max(coreSize, maxSize),
        60L,
        TimeUnit.SECONDS,
        queue,
        SleuthThreadFactory.daemon(threadName),
        new ThreadPoolExecutor.AbortPolicy()
    );
    tpe.allowCoreThreadTimeOut(true);
    return tpe;
}
```

In the constructor, create both executors:

```java
this.shortCommandExecutor = newExecutor(
    "sleuth-cmd-short",
    perf.getCommandExecutorCoreSize(),
    perf.getCommandExecutorMaxSize(),
    perf.getCommandExecutorQueueCapacity()
);
this.streamCommandExecutor = newExecutor(
    "sleuth-cmd-stream",
    perf.getCommandStreamExecutorCoreSize(),
    perf.getCommandStreamExecutorMaxSize(),
    perf.getCommandStreamExecutorQueueCapacity()
);
```

Use `shortCommandExecutor` in `executeWithTimeout(...)` and `streamCommandExecutor` in `executeStreamWithTimeout(...)`. Replace remove calls with the matching executor. Replace queue-full messages with the pool-specific messages from the spec.

Update `shutdown()`:

```java
public void shutdown() {
    SleuthExecutors.shutdownAndAwait(shortCommandExecutor, "short-command-exec", 5, TimeUnit.SECONDS);
    SleuthExecutors.shutdownAndAwait(streamCommandExecutor, "stream-command-exec", 5, TimeUnit.SECONDS);
}
```

- [ ] **Step 5: Run the isolation test again**

Run:

```bash
mvn -pl core -am -Dtest=CommandExecutionEngineIsolationTest test
```

Expected: PASS. The short command returns `ok` while a stream worker is blocked.

- [ ] **Step 6: Commit**

```bash
git add core/src/test/java/com/javasleuth/command/CommandExecutionEngineIsolationTest.java \
  foundation/src/main/java/com/javasleuth/foundation/config/schema/SleuthConfigSchema.java \
  foundation/src/main/java/com/javasleuth/foundation/config/model/PerformanceConfig.java \
  foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfigParser.java \
  core/src/main/java/com/javasleuth/core/command/pipeline/CommandExecutionEngine.java
git commit -m "refactor: isolate stream command execution"
```

## Task 2: Add Cancellation Token to Foreground Streams and Jobs

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/command/CancellationToken.java`
- Create: `core/src/main/java/com/javasleuth/core/command/CancellationTokenSource.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandContext.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/pipeline/CommandExecutionEngine.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/JobManager.java`
- Test: `core/src/test/java/com/javasleuth/command/CommandPipelineStreamExecutionTest.java`
- Test: `core/src/test/java/com/javasleuth/command/JobManagerConcurrencyTest.java`

- [ ] **Step 1: Add foreground stream cancellation test**

Add this test method to `CommandPipelineStreamExecutionTest` or create `CommandExecutionCancellationTest` if the existing file is tightly scoped:

```java
@Test
public void streamTimeoutCancelsTokenVisibleToCommand() throws Exception {
    ProductionConfig config = new ProductionConfig();
    config.setRuntimeConfig("performance.command.stream.executor.core", "1");
    config.setRuntimeConfig("performance.command.stream.executor.max", "1");
    config.setRuntimeConfig("performance.command.timeout", "100");
    final CommandExecutionEngine engine = new CommandExecutionEngine(config);
    final CountDownLatch started = new CountDownLatch(1);
    final AtomicBoolean observedCancelled = new AtomicBoolean(false);

    StreamCommand stream = new StreamCommand() {
        @Override
        public void executeStream(String[] args, StreamSink sink) throws Exception {
            started.countDown();
            while (true) {
                CommandContext ctx = CommandContextHolder.get();
                if (ctx != null && ctx.getCancellationToken().isCancelled()) {
                    observedCancelled.set(true);
                    return;
                }
                Thread.sleep(10L);
            }
        }

        @Override
        public String execute(String[] args) {
            return "";
        }

        @Override
        public String getDescription() {
            return "token-aware stream";
        }
    };

    try {
        engine.executeStream(stream, new String[] {"watch"}, CommandMeta.operator(false, true),
            100L, noOpSink(), new CommandContext("client", "test", "session", true));
        Assert.fail("expected timeout");
    } catch (Exception expected) {
        Assert.assertTrue(expected.getMessage().contains("timed out"));
    } finally {
        engine.shutdown();
    }

    Assert.assertTrue(started.await(1, TimeUnit.SECONDS));
    Assert.assertTrue("stream command did not observe cancellation", observedCancelled.get());
}
```

- [ ] **Step 2: Add background job cancellation test**

Add this test method to `JobManagerConcurrencyTest`:

```java
@Test
public void stopCancelsTokenVisibleToBackgroundJob() throws Exception {
    final JobManager jobs = new JobManager();
    jobs.configureExecution(1, 1);
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch observed = new CountDownLatch(1);

    String jobId = jobs.submitStreamJob("test", "test", new JobManager.StreamJob() {
        @Override
        public void run(StreamSink sink) throws Exception {
            started.countDown();
            while (true) {
                CommandContext ctx = CommandContextHolder.get();
                if (ctx != null && ctx.getCancellationToken().isCancelled()) {
                    observed.countDown();
                    return;
                }
                Thread.sleep(10L);
            }
        }
    });

    Assert.assertTrue(started.await(1, TimeUnit.SECONDS));
    Assert.assertTrue(jobs.stop(jobId));
    Assert.assertTrue("job did not observe cancellation", observed.await(1, TimeUnit.SECONDS));
    jobs.shutdown("test");
}
```

- [ ] **Step 3: Run cancellation tests and confirm failure**

Run:

```bash
mvn -pl core -am -Dtest=CommandPipelineStreamExecutionTest,JobManagerConcurrencyTest test
```

Expected: FAIL with missing `getCancellationToken()` or assertion failure because cancellation is not visible through context.

- [ ] **Step 4: Add cancellation primitives**

Create `CancellationToken.java`:

```java
package com.javasleuth.core.command;

public interface CancellationToken {
    CancellationToken NONE = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void throwIfCancelled() throws InterruptedException {
        }
    };

    boolean isCancelled();

    void throwIfCancelled() throws InterruptedException;
}
```

Create `CancellationTokenSource.java`:

```java
package com.javasleuth.core.command;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationTokenSource {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CancellationToken token = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void throwIfCancelled() throws InterruptedException {
            if (cancelled.get()) {
                throw new InterruptedException("cancelled");
            }
        }
    };

    public CancellationToken token() {
        return token;
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
```

- [ ] **Step 5: Extend `CommandContext`**

Add fields and constructors without removing existing signatures:

```java
private final CancellationToken cancellationToken;

public CommandContext(String clientId,
                      String clientInfo,
                      String sessionId,
                      String connId,
                      String commandName,
                      boolean streaming,
                      ClientSession clientSession,
                      CancellationToken cancellationToken) {
    this.clientId = clientId;
    this.clientInfo = clientInfo;
    this.sessionId = sessionId;
    this.connId = connId;
    this.commandName = commandName;
    this.streaming = streaming;
    this.clientSession = clientSession;
    this.cancellationToken = cancellationToken != null ? cancellationToken : CancellationToken.NONE;
}

public CancellationToken getCancellationToken() {
    return cancellationToken != null ? cancellationToken : CancellationToken.NONE;
}

public CommandContext withCancellationToken(CancellationToken token) {
    return new CommandContext(clientId, clientInfo, sessionId, connId, commandName, streaming, clientSession, token);
}
```

Change existing constructors to delegate to the new constructor with `CancellationToken.NONE`.

- [ ] **Step 6: Wire cancellation into stream execution**

In `CommandExecutionEngine.executeStreamWithTimeout(...)`, create `CancellationTokenSource source = new CancellationTokenSource();` before building the task. Use this context in the task:

```java
CommandContext taskContext = context != null ? context.withCancellationToken(source.token()) : null;
applyContext(taskContext);
```

On timeout and interruption, call `source.cancel()` before `task.cancel(true)`. In the `ExecutionException` branch, preserve existing `ClientDisconnectedException` behavior and do not wrap it.

- [ ] **Step 7: Wire cancellation into `JobManager`**

Inside the private `Job` class, add:

```java
private final CancellationTokenSource cancelSource = new CancellationTokenSource();
```

In `stop(String jobId)`, replace `j.cancelled.set(true)` with:

```java
j.cancelSource.cancel();
```

When setting command context for the job thread, use:

```java
CommandContext jobContext = capturedContext != null
    ? capturedContext.withCancellationToken(j.cancelSource.token())
    : new CommandContext(null, null, null, false).withCancellationToken(j.cancelSource.token());
CommandContextHolder.set(jobContext);
```

Replace `j.cancelled.get()` checks with `j.cancelSource.isCancelled()`.

- [ ] **Step 8: Run cancellation tests**

Run:

```bash
mvn -pl core -am -Dtest=CommandPipelineStreamExecutionTest,JobManagerConcurrencyTest test
```

Expected: PASS for the new cancellation tests and existing tests in those classes.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/com/javasleuth/core/command/CancellationToken.java \
  core/src/main/java/com/javasleuth/core/command/CancellationTokenSource.java \
  core/src/main/java/com/javasleuth/core/command/CommandContext.java \
  core/src/main/java/com/javasleuth/core/command/pipeline/CommandExecutionEngine.java \
  core/src/main/java/com/javasleuth/core/command/JobManager.java \
  core/src/test/java/com/javasleuth/command/CommandPipelineStreamExecutionTest.java \
  core/src/test/java/com/javasleuth/command/JobManagerConcurrencyTest.java
git commit -m "feat: propagate command cancellation tokens"
```

## Task 3: Make Long-Running Loops Observe Cancellation

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java`

- [ ] **Step 1: Add token helper to each long-running implementation**

In each file, add imports for `CancellationToken`, `CommandContext`, and `CommandContextHolder` if missing. Add this helper near existing private helpers:

```java
private static CancellationToken currentCancellationToken() {
    CommandContext ctx = CommandContextHolder.get();
    return ctx != null ? ctx.getCancellationToken() : CancellationToken.NONE;
}
```

- [ ] **Step 2: Update polling loops**

In `WatchCommand` and `TraceCommand`, wrap the event collection loop with the token:

```java
CancellationToken token = currentCancellationToken();
while (!token.isCancelled() && eventCount < maxCount) {
    long elapsed = System.currentTimeMillis() - startTime;
    long remainingTime = timeoutMs - elapsed;
    if (remainingTime <= 0) {
        appendOrSend(result, sink, "\nTimeout reached");
        break;
    }
    WatchResult watchResult = resultQueue.poll(Math.min(remainingTime, 1000L), TimeUnit.MILLISECONDS);
    token.throwIfCancelled();
    if (watchResult == null) {
        continue;
    }
    // keep the existing formatting and increment logic here
}
```

For `TraceCommand`, use the existing `TraceResult` variable name and preserve aggregation/rendering code.

- [ ] **Step 3: Update sleep loop in `MonitorCommand`**

At the start of the monitor loop, create `CancellationToken token = currentCancellationToken();`. Change the loop condition and sleep block:

```java
for (int i = 0; i < rounds && !token.isCancelled(); i++) {
    try {
        Thread.sleep(Math.max(1, intervalMs));
        token.throwIfCancelled();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        appendOrSend(out, sink, "\nMonitor interrupted");
        break;
    }
    // keep existing snapshot logic
}
```

- [ ] **Step 4: Update `TtRecordEngine` and `StackTraceLiteEngine` polling loops**

Use the same pattern as watch/trace. Keep existing output formatting and cleanup logic. The loop must stop when `token.isCancelled()` returns true and must call `token.throwIfCancelled()` after blocking `poll(...)`.

- [ ] **Step 5: Run targeted loop-related tests**

Run:

```bash
mvn -pl core -am -Dtest=CommandPipelineStreamExecutionTest,JobManagerConcurrencyTest test
```

Expected: PASS. The tests from Task 2 should still pass, and no long-running command should hang after cancellation.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java \
  core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java \
  core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java \
  core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java \
  core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java
git commit -m "fix: stop long-running commands via cancellation token"
```

## Task 4: Validate Runtime Config Through Schema

**Files:**
- Create: `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigValidationResult.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigKey.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/ProductionConfig.java`
- Test: `core/src/test/java/com/javasleuth/config/ProductionConfigRuntimeValidationTest.java`

- [ ] **Step 1: Write failing runtime validation tests**

Create `ProductionConfigRuntimeValidationTest.java`:

```java
package com.javasleuth.config;

import com.javasleuth.foundation.config.ProductionConfig;
import org.junit.Assert;
import org.junit.Test;

public class ProductionConfigRuntimeValidationTest {
    @Test
    public void rejectsOutOfRangeKnownKey() {
        ProductionConfig config = new ProductionConfig();
        try {
            config.setRuntimeConfig("monitoring.watch.queue.capacity", "0");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("monitoring.watch.queue.capacity"));
        }
    }

    @Test
    public void rejectsInvalidBoolean() {
        ProductionConfig config = new ProductionConfig();
        try {
            config.setRuntimeConfig("monitoring.trace.drop.on.full", "maybe");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("not_boolean"));
        }
    }

    @Test
    public void rejectsUnknownRuntimeKey() {
        ProductionConfig config = new ProductionConfig();
        try {
            config.setRuntimeConfig("unknown.runtime.key", "value");
            Assert.fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("Unknown config key"));
        }
    }

    @Test
    public void normalizesBooleanBeforeStorage() {
        ProductionConfig config = new ProductionConfig();
        config.setRuntimeConfig("monitoring.trace.drop.on.full", "FALSE");
        Assert.assertEquals("false", config.getString("monitoring.trace.drop.on.full", "true"));
    }
}
```

- [ ] **Step 2: Run failing tests**

Run:

```bash
mvn -pl core -am -Dtest=ProductionConfigRuntimeValidationTest test
```

Expected: FAIL because unknown keys and invalid booleans are currently accepted.

- [ ] **Step 3: Create `ConfigValidationResult`**

Create `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigValidationResult.java`:

```java
package com.javasleuth.foundation.config.schema;

public final class ConfigValidationResult {
    private final boolean valid;
    private final String normalizedValue;
    private final String error;
    private final boolean sensitive;

    private ConfigValidationResult(boolean valid, String normalizedValue, String error, boolean sensitive) {
        this.valid = valid;
        this.normalizedValue = normalizedValue;
        this.error = error;
        this.sensitive = sensitive;
    }

    public static ConfigValidationResult ok(String normalizedValue, boolean sensitive) {
        return new ConfigValidationResult(true, normalizedValue, null, sensitive);
    }

    public static ConfigValidationResult invalid(String error, boolean sensitive) {
        return new ConfigValidationResult(false, null, error, sensitive);
    }

    public boolean isValid() {
        return valid;
    }

    public String getNormalizedValue() {
        return normalizedValue;
    }

    public String getError() {
        return error;
    }

    public boolean isSensitive() {
        return sensitive;
    }
}
```

- [ ] **Step 4: Add `ConfigKey.validateRuntimeValue`**

Add this public method to `ConfigKey`:

```java
public ConfigValidationResult validateRuntimeValue(String rawValue) {
    String raw = rawValue == null ? "" : rawValue.trim();
    if (raw.isEmpty() && valueType != ValueType.STRING) {
        return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (empty)", sensitive);
    }
    try {
        switch (valueType) {
            case STRING:
                if (requireNonBlank && raw.trim().isEmpty()) {
                    return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (blank)", sensitive);
                }
                if (allowedStringValuesLower != null && !allowedStringValuesLower.isEmpty()) {
                    String lower = raw.toLowerCase(Locale.ROOT);
                    if (!allowedStringValuesLower.contains(lower)) {
                        return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (unsupported)", sensitive);
                    }
                }
                return ConfigValidationResult.ok(raw, sensitive);
            case INT:
                int intValue = Integer.parseInt(raw);
                if (minLongInclusive != null && intValue < minLongInclusive.longValue()) {
                    return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (out_of_range)", sensitive);
                }
                if (maxLongInclusive != null && intValue > maxLongInclusive.longValue()) {
                    return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (out_of_range)", sensitive);
                }
                return ConfigValidationResult.ok(String.valueOf(intValue), sensitive);
            case LONG:
                long longValue = Long.parseLong(raw);
                if (minLongInclusive != null && longValue < minLongInclusive.longValue()) {
                    return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (out_of_range)", sensitive);
                }
                if (maxLongInclusive != null && longValue > maxLongInclusive.longValue()) {
                    return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (out_of_range)", sensitive);
                }
                return ConfigValidationResult.ok(String.valueOf(longValue), sensitive);
            case DOUBLE:
                double doubleValue = Double.parseDouble(raw);
                if (minDoubleInclusive != null && doubleValue < minDoubleInclusive.doubleValue()) {
                    return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (out_of_range)", sensitive);
                }
                if (maxDoubleInclusive != null && doubleValue > maxDoubleInclusive.doubleValue()) {
                    return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (out_of_range)", sensitive);
                }
                return ConfigValidationResult.ok(String.valueOf(doubleValue), sensitive);
            case BOOLEAN:
                String lower = raw.toLowerCase(Locale.ROOT);
                if (!"true".equals(lower) && !"false".equals(lower)) {
                    return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (not_boolean)", sensitive);
                }
                return ConfigValidationResult.ok(lower, sensitive);
            default:
                return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (unsupported_type)", sensitive);
        }
    } catch (NumberFormatException e) {
        return ConfigValidationResult.invalid("Invalid config " + key + "=" + rawValue + " (not_" + valueType.name().toLowerCase(Locale.ROOT) + ")", sensitive);
    }
}
```

- [ ] **Step 5: Enforce validation in `ProductionConfig`**

Update `setRuntimeConfig(String key, String value, ConfigUpdateSource source)`:

```java
public void setRuntimeConfig(String key, String value, ConfigUpdateSource source) {
    validateRuntimeOverrideKey(key);
    ConfigKey<?> schemaKey = SleuthConfigSchema.byKey(key);
    if (schemaKey == null) {
        throw new IllegalArgumentException("Unknown config key: " + key);
    }
    ConfigValidationResult result = schemaKey.validateRuntimeValue(value);
    if (result == null || !result.isValid()) {
        throw new IllegalArgumentException(result != null ? result.getError() : "Invalid config " + key);
    }
    runtimeStore.set(key, result.getNormalizedValue(), source);
}
```

Add imports for `ConfigKey` and `ConfigValidationResult`.

- [ ] **Step 6: Run runtime validation tests**

Run:

```bash
mvn -pl core -am -Dtest=ProductionConfigRuntimeValidationTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigValidationResult.java \
  foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigKey.java \
  foundation/src/main/java/com/javasleuth/foundation/config/ProductionConfig.java \
  core/src/test/java/com/javasleuth/config/ProductionConfigRuntimeValidationTest.java
git commit -m "fix: validate runtime config through schema"
```

## Task 5: Replace Raw Config Reads and Add VmTool Schema

**Files:**
- Create: `foundation/src/main/java/com/javasleuth/foundation/config/model/VmToolConfig.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/schema/SleuthConfigSchema.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfig.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfigParser.java`
- Modify: `core/src/main/java/com/javasleuth/core/enhancement/SleuthClassFileTransformer.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`
- Test: `core/src/test/java/com/javasleuth/config/SleuthConfigParserTest.java`

- [ ] **Step 1: Add typed vmtool config test**

Add this test to `SleuthConfigParserTest`:

```java
@Test
public void parsesVmToolConfig() {
    ProductionConfig config = new ProductionConfig();
    config.setRuntimeConfig("vmtool.track.max.entries", "123");
    config.setRuntimeConfig("vmtool.track.class.limit", "45");

    SleuthConfig typed = SleuthConfigParser.parse(config.snapshot());

    Assert.assertEquals(123, typed.vmTool().getTrackMaxEntries());
    Assert.assertEquals(45, typed.vmTool().getTrackClassLimit());
}
```

- [ ] **Step 2: Run parser test and confirm failure**

Run:

```bash
mvn -pl core -am -Dtest=SleuthConfigParserTest#parsesVmToolConfig test
```

Expected: FAIL because `vmTool()` and the schema keys do not exist.

- [ ] **Step 3: Add vmtool schema and model**

In `SleuthConfigSchema`, add:

```java
public static final ConfigKey<Integer> VMTOOL_TRACK_MAX_ENTRIES = register(
    ConfigKey.intKey("vmtool.track.max.entries")
        .defaultValue(500)
        .longRange(1, 100000)
        .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
        .build()
);

public static final ConfigKey<Integer> VMTOOL_TRACK_CLASS_LIMIT = register(
    ConfigKey.intKey("vmtool.track.class.limit")
        .defaultValue(50)
        .longRange(1, 10000)
        .failurePolicy(ConfigKey.FailurePolicy.CLAMP_AND_WARN)
        .build()
);
```

Create `VmToolConfig.java`:

```java
package com.javasleuth.foundation.config.model;

public final class VmToolConfig {
    private final int trackMaxEntries;
    private final int trackClassLimit;

    public VmToolConfig(int trackMaxEntries, int trackClassLimit) {
        this.trackMaxEntries = trackMaxEntries;
        this.trackClassLimit = trackClassLimit;
    }

    public int getTrackMaxEntries() {
        return trackMaxEntries;
    }

    public int getTrackClassLimit() {
        return trackClassLimit;
    }
}
```

Add `VmToolConfig vmTool` to `SleuthConfig`, constructor, and getter:

```java
public VmToolConfig vmTool() {
    return vmTool;
}
```

In `SleuthConfigParser`, add:

```java
private static VmToolConfig parseVmTool(ConfigView config) {
    int maxEntries = SleuthConfigSchema.VMTOOL_TRACK_MAX_ENTRIES.read(config);
    int classLimit = SleuthConfigSchema.VMTOOL_TRACK_CLASS_LIMIT.read(config);
    return new VmToolConfig(maxEntries, classLimit);
}
```

Call `parseVmTool(config)` in `parse(...)` and pass the result to `SleuthConfig`.

- [ ] **Step 4: Replace raw reads**

Use schema or typed snapshots:

```java
MonitoringConfig monitoring = SleuthConfigParser.parse(config.snapshot()).monitoring();
BlockingQueue<WatchResult> resultQueue = new LinkedBlockingQueue<WatchResult>(monitoring.getWatchQueueCapacity());
boolean dropOnFull = monitoring.isWatchDropOnFull();
```

For trace:

```java
MonitoringConfig monitoring = SleuthConfigParser.parse(config.snapshot()).monitoring();
BlockingQueue<TraceResult> resultQueue = new LinkedBlockingQueue<TraceResult>(monitoring.getTraceQueueCapacity());
boolean dropOnFull = monitoring.isTraceDropOnFull();
```

For transformer cooldowns:

```java
long cooldownMs = SleuthConfigSchema.ENHANCEMENT_FAILURE_COOLDOWN_MS.read(config);
long logIntervalMs = SleuthConfigSchema.ENHANCEMENT_FAILURE_LOG_INTERVAL_MS.read(config);
```

For vmtool defaults:

```java
VmToolConfig vmTool = SleuthConfigParser.parse(config.snapshot()).vmTool();
int maxEntries = vmTool.getTrackMaxEntries();
int classLimit = vmTool.getTrackClassLimit();
```

For status output, parse one typed snapshot near the start of `execute(...)`:

```java
SleuthConfig typed = SleuthConfigParser.parse(config.snapshot());
```

Then render values from `typed.server()`, `typed.security()`, `typed.protocol()`, and `typed.monitoring()` instead of raw getters.

- [ ] **Step 5: Run config parser and status tests**

Run:

```bash
mvn -pl core -am -Dtest=SleuthConfigParserTest,ConfigSemanticsTest,DefaultConfigConsistencyTest test
```

Expected: PASS.

- [ ] **Step 6: Search for remaining raw reads in target areas**

Run:

```bash
rg -n "config\.get(Int|Boolean|Long|Double)" core/src/main/java/com/javasleuth/core/{command,enhancement}
```

Expected: no matches in `WatchCommand`, `TraceCommand`, `StatusCommand`, `VmToolCommand`, `TtRecordEngine`, `StackTraceLiteEngine`, or `SleuthClassFileTransformer` unless the line is deliberately outside runtime schema coverage and documented in the commit message.

- [ ] **Step 7: Commit**

```bash
git add foundation/src/main/java/com/javasleuth/foundation/config/model/VmToolConfig.java \
  foundation/src/main/java/com/javasleuth/foundation/config/schema/SleuthConfigSchema.java \
  foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfig.java \
  foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfigParser.java \
  core/src/main/java/com/javasleuth/core/enhancement/SleuthClassFileTransformer.java \
  core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java \
  core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java \
  core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java \
  core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java \
  core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java \
  core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java \
  core/src/test/java/com/javasleuth/config/SleuthConfigParserTest.java
git commit -m "refactor: read runtime config through schema"
```

## Task 6: Add Bootstrap Contract Version Diagnostics

**Files:**
- Modify: `bootstrap/src/main/java/com/javasleuth/bootstrap/agent/AgentLifecycle.java`
- Modify: `agent/src/main/java/com/javasleuth/agent/CrossClassLoaderFacade.java`
- Modify: `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`
- Modify: `core/src/main/java/com/javasleuth/core/agent/runtime/BootstrapBridge.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`
- Modify: `docs/dev/cross-classloader-contract.md`
- Test: `core/src/test/java/com/javasleuth/bootstrap/agent/AgentLifecycleTest.java`
- Test: `core/src/test/java/com/javasleuth/agent/CrossClassLoaderReflectionContractTest.java`
- Test: `agent/src/test/java/com/javasleuth/agent/CrossClassLoaderFacadeContractTest.java`

- [ ] **Step 1: Add contract version tests**

In `AgentLifecycleTest`, add:

```java
@Test
public void contractVersionIsOne() {
    Assert.assertEquals(1, AgentLifecycle.contractVersion());
}
```

In `CrossClassLoaderReflectionContractTest`, add reflection assertion:

```java
@Test
public void lifecycleExposesContractVersion() throws Exception {
    Class<?> lifecycle = Class.forName("com.javasleuth.bootstrap.agent.AgentLifecycle");
    Method method = lifecycle.getMethod("contractVersion");
    Assert.assertEquals(Integer.TYPE, method.getReturnType());
}
```

- [ ] **Step 2: Run failing contract tests**

Run:

```bash
mvn -pl core -am -Dtest=AgentLifecycleTest,CrossClassLoaderReflectionContractTest test
```

Expected: FAIL because `contractVersion()` does not exist.

- [ ] **Step 3: Add bootstrap contract method**

In `AgentLifecycle`, add:

```java
public static int contractVersion() {
    return 1;
}
```

- [ ] **Step 4: Add thin-agent diagnostic check**

In `CrossClassLoaderFacade`, add constants:

```java
static final int MIN_BOOTSTRAP_CONTRACT_VERSION = 1;
static final int MAX_BOOTSTRAP_CONTRACT_VERSION = 1;
```

Add nested result type:

```java
static final class BootstrapContractCheck {
    enum Status {
        OK,
        MISSING_CLASS,
        MISSING_METHOD,
        BAD_RETURN_TYPE,
        INVOCATION_FAILED,
        INCOMPATIBLE_VERSION
    }

    private final Status status;
    private final Integer foundVersion;
    private final String detail;

    private BootstrapContractCheck(Status status, Integer foundVersion, String detail) {
        this.status = status;
        this.foundVersion = foundVersion;
        this.detail = detail;
    }

    static BootstrapContractCheck ok(int version) {
        return new BootstrapContractCheck(Status.OK, Integer.valueOf(version), null);
    }

    static BootstrapContractCheck failed(Status status, Integer foundVersion, String detail) {
        return new BootstrapContractCheck(status, foundVersion, detail);
    }

    boolean isOk() {
        return status == Status.OK;
    }

    String userMessage() {
        if (status == Status.OK) {
            return "Bootstrap bridge contract OK: version=" + foundVersion;
        }
        if (status == Status.INCOMPATIBLE_VERSION) {
            return "Bootstrap bridge contract mismatch: expected " + MIN_BOOTSTRAP_CONTRACT_VERSION
                + ".." + MAX_BOOTSTRAP_CONTRACT_VERSION + ", found " + foundVersion;
        }
        if (status == Status.MISSING_METHOD) {
            return "Bootstrap bridge incomplete: missing AgentLifecycle.contractVersion()";
        }
        if (status == Status.MISSING_CLASS) {
            return "Bootstrap bridge unavailable: missing " + BOOTSTRAP_AGENT_LIFECYCLE_CLASS;
        }
        return "Bootstrap bridge contract check failed: " + status.name() + (detail != null ? " (" + detail + ")" : "");
    }
}
```

Add method:

```java
static BootstrapContractCheck verifyBootstrapContract() {
    try {
        Class<?> lifecycle = Class.forName(BOOTSTRAP_AGENT_LIFECYCLE_CLASS, false, null);
        Method method = lifecycle.getMethod("contractVersion");
        if (method.getReturnType() != Integer.TYPE && method.getReturnType() != Integer.class) {
            return BootstrapContractCheck.failed(BootstrapContractCheck.Status.BAD_RETURN_TYPE, null, method.getReturnType().getName());
        }
        Object result = method.invoke(null);
        if (!(result instanceof Number)) {
            return BootstrapContractCheck.failed(BootstrapContractCheck.Status.BAD_RETURN_TYPE, null, String.valueOf(result));
        }
        int version = ((Number) result).intValue();
        if (version < MIN_BOOTSTRAP_CONTRACT_VERSION || version > MAX_BOOTSTRAP_CONTRACT_VERSION) {
            return BootstrapContractCheck.failed(BootstrapContractCheck.Status.INCOMPATIBLE_VERSION, Integer.valueOf(version), null);
        }
        return BootstrapContractCheck.ok(version);
    } catch (ClassNotFoundException e) {
        return BootstrapContractCheck.failed(BootstrapContractCheck.Status.MISSING_CLASS, null, e.getMessage());
    } catch (NoSuchMethodException e) {
        return BootstrapContractCheck.failed(BootstrapContractCheck.Status.MISSING_METHOD, null, e.getMessage());
    } catch (Throwable t) {
        return BootstrapContractCheck.failed(BootstrapContractCheck.Status.INVOCATION_FAILED, null, t.getClass().getName());
    }
}
```

- [ ] **Step 5: Abort early in `SleuthAgent`**

After `isBootstrapBridgeAvailable()` and before `tryBeginAttachOrZero()`, add:

```java
CrossClassLoaderFacade.BootstrapContractCheck contract = CrossClassLoaderFacade.verifyBootstrapContract();
if (contract == null || !contract.isOk()) {
    log(contract != null ? contract.userMessage() : "Bootstrap bridge contract check failed");
    return;
}
```

Use the existing logging method in `SleuthAgent`; do not introduce a new logger.

- [ ] **Step 6: Surface status in core**

In `BootstrapBridge`, add:

```java
public static final String AGENT_LIFECYCLE = "com.javasleuth.bootstrap.agent.AgentLifecycle";
public static final int MIN_CONTRACT_VERSION = 1;
public static final int MAX_CONTRACT_VERSION = 1;
```

Add:

```java
public static int bootstrapContractVersion() {
    try {
        Class<?> lifecycle = Class.forName(AGENT_LIFECYCLE, false, null);
        java.lang.reflect.Method method = lifecycle.getMethod("contractVersion");
        Object result = method.invoke(null);
        return result instanceof Number ? ((Number) result).intValue() : -1;
    } catch (Throwable ignore) {
        return -1;
    }
}

public static String describeContractStatus() {
    int version = bootstrapContractVersion();
    if (version >= MIN_CONTRACT_VERSION && version <= MAX_CONTRACT_VERSION) {
        return "OK (contract=" + version + ")";
    }
    if (version < 0) {
        return "UNAVAILABLE (missing contractVersion)";
    }
    return "INCOMPATIBLE (expected " + MIN_CONTRACT_VERSION + ".." + MAX_CONTRACT_VERSION + ", found " + version + ")";
}
```

In `StatusCommand`, append `Bootstrap contract: ` plus `BootstrapBridge.describeContractStatus()` near the existing bootstrap bridge status.

- [ ] **Step 7: Update contract docs**

In `docs/dev/cross-classloader-contract.md`, add `contractVersion() : int` under `AgentLifecycle` and add this rule:

```markdown
The bootstrap bridge contract version is an ABI version. It starts at `1` and changes only when the thin-agent reflection contract or bootstrap-visible runtime contract becomes incompatible.
```

- [ ] **Step 8: Run contract tests**

Run:

```bash
mvn -pl agent,core -am -Dtest=CrossClassLoaderFacadeContractTest,CrossClassLoaderReflectionContractTest,AgentLifecycleTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add bootstrap/src/main/java/com/javasleuth/bootstrap/agent/AgentLifecycle.java \
  agent/src/main/java/com/javasleuth/agent/CrossClassLoaderFacade.java \
  agent/src/main/java/com/javasleuth/agent/SleuthAgent.java \
  core/src/main/java/com/javasleuth/core/agent/runtime/BootstrapBridge.java \
  core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java \
  docs/dev/cross-classloader-contract.md \
  core/src/test/java/com/javasleuth/bootstrap/agent/AgentLifecycleTest.java \
  core/src/test/java/com/javasleuth/agent/CrossClassLoaderReflectionContractTest.java \
  agent/src/test/java/com/javasleuth/agent/CrossClassLoaderFacadeContractTest.java
git commit -m "feat: verify bootstrap contract version"
```

## Task 7: Add CommandSpec Parser and Help Renderer

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/command/SpecBackedCommand.java`
- Create: `core/src/main/java/com/javasleuth/core/command/spec/CommandSpec.java`
- Create: `core/src/main/java/com/javasleuth/core/command/spec/SubcommandSpec.java`
- Create: `core/src/main/java/com/javasleuth/core/command/spec/ArgumentSpec.java`
- Create: `core/src/main/java/com/javasleuth/core/command/spec/OptionSpec.java`
- Create: `core/src/main/java/com/javasleuth/core/command/spec/ParsedCommand.java`
- Create: `core/src/main/java/com/javasleuth/core/command/spec/CommandSpecParser.java`
- Create: `core/src/main/java/com/javasleuth/core/command/spec/CommandHelpRenderer.java`
- Create: `core/src/main/java/com/javasleuth/core/command/spec/CommandSpecParseException.java`
- Test: `core/src/test/java/com/javasleuth/command/spec/CommandSpecParserTest.java`
- Test: `core/src/test/java/com/javasleuth/command/spec/CommandHelpRendererTest.java`

- [ ] **Step 1: Write parser tests**

Create `CommandSpecParserTest.java` with tests for aliases, missing values, unknown options, invalid integers, range errors, duplicate non-repeatable options, and repeatable option order. Use this helper spec inside the test:

```java
private static CommandSpec sampleSpec() {
    return CommandSpec.builder("watch")
        .description("Watch method execution")
        .usage("watch <class-pattern> <method-pattern> [options]")
        .meta(CommandMeta.operator(false, true))
        .argument(ArgumentSpec.required("class-pattern"))
        .argument(ArgumentSpec.required("method-pattern"))
        .option(OptionSpec.integer("count").alias("-n").alias("--count").defaultValue(100).range(1, 100000).build())
        .option(OptionSpec.string("condition").alias("--condition").repeatable(true).build())
        .option(OptionSpec.flag("bg").alias("--bg").build())
        .build();
}
```

Include assertions:

```java
ParsedCommand parsed = CommandSpecParser.parse(sampleSpec(), new String[] {"watch", "A", "m", "-n", "5", "--condition", "cost:gt:1", "--condition=thread:eq:main", "--bg"});
Assert.assertEquals("A", parsed.argument("class-pattern"));
Assert.assertEquals(5, parsed.intOption("count"));
Assert.assertEquals(Boolean.TRUE, parsed.booleanOption("bg"));
Assert.assertEquals(2, parsed.optionValues("condition").size());
```

- [ ] **Step 2: Write help renderer test**

Create `CommandHelpRendererTest.java`:

```java
@Test
public void rendersUsageOptionsAndExamples() {
    CommandSpec spec = CommandSpec.builder("monitor")
        .description("Monitor method statistics")
        .usage("monitor <class-pattern> <method-pattern> [options]")
        .meta(CommandMeta.operator(false, true))
        .argument(ArgumentSpec.required("class-pattern"))
        .argument(ArgumentSpec.required("method-pattern"))
        .option(OptionSpec.longNumber("interval").alias("-i").alias("--interval").defaultValue(5000L).range(1L, 86400000L).build())
        .example("monitor *Service* doWork -i 1000")
        .build();

    String help = CommandHelpRenderer.render(spec);
    Assert.assertTrue(help.contains("monitor <class-pattern> <method-pattern> [options]"));
    Assert.assertTrue(help.contains("-i, --interval"));
    Assert.assertTrue(help.contains("default: 5000"));
    Assert.assertTrue(help.contains("monitor *Service* doWork -i 1000"));
}
```

- [ ] **Step 3: Run failing parser tests**

Run:

```bash
mvn -pl core -am -Dtest=CommandSpecParserTest,CommandHelpRendererTest test
```

Expected: FAIL because the `command.spec` package does not exist.

- [ ] **Step 4: Create spec model classes**

Create immutable model classes with builder APIs matching the tests. Minimum required APIs:

```java
CommandSpec.builder(String name)
    .description(String description)
    .usage(String usage)
    .meta(CommandMeta meta)
    .argument(ArgumentSpec argument)
    .option(OptionSpec option)
    .subcommand(SubcommandSpec subcommand)
    .example(String example)
    .build();
```

```java
ArgumentSpec.required(String name);
ArgumentSpec.optional(String name);
```

```java
OptionSpec.flag(String name);
OptionSpec.string(String name);
OptionSpec.integer(String name);
OptionSpec.longNumber(String name);
OptionSpec.Builder alias(String alias);
OptionSpec.Builder defaultValue(Object value);
OptionSpec.Builder range(long min, long max);
OptionSpec.Builder repeatable(boolean repeatable);
OptionSpec build();
```

```java
ParsedCommand argument(String name);
ParsedCommand option(String name);
ParsedCommand intOption(String name);
ParsedCommand longOption(String name);
ParsedCommand booleanOption(String name);
ParsedCommand optionValues(String name);
ParsedCommand isHelpRequested();
```

Use defensive copies and unmodifiable collections for all getters.

- [ ] **Step 5: Implement parser behavior**

`CommandSpecParser.parse(CommandSpec spec, String[] args)` must:

- Skip `args[0]` as the command name.
- Return `ParsedCommand` with `helpRequested=true` when any token equals `-h`, `--help`, or `help`.
- Match options by aliases and canonical names.
- Support `--key=value` by splitting at the first `=`.
- Support `--key value` and `-k value` for value options.
- Store default values before parsing explicit values.
- Fail with `CommandSpecParseException` codes `E_ARGS_UNKNOWN`, `E_ARGS_MISSING`, `E_ARGS_INVALID`, `E_ARGS_RANGE`, or `E_ARGS_DUPLICATE`.

The exception constructor must be:

```java
public CommandSpecParseException(String code, String message) {
    super(code + ": " + message);
    this.code = code;
}
```

- [ ] **Step 6: Implement help renderer**

`CommandHelpRenderer.render(CommandSpec spec)` must include command name, description, usage, arguments, options, subcommands, and examples when present. Render option aliases in input order and include default/range text when configured.

- [ ] **Step 7: Run parser and help tests**

Run:

```bash
mvn -pl core -am -Dtest=CommandSpecParserTest,CommandHelpRendererTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/javasleuth/core/command/SpecBackedCommand.java \
  core/src/main/java/com/javasleuth/core/command/spec \
  core/src/test/java/com/javasleuth/command/spec/CommandSpecParserTest.java \
  core/src/test/java/com/javasleuth/command/spec/CommandHelpRendererTest.java
git commit -m "feat: add command specification parser"
```

## Task 8: Integrate CommandSpec With Descriptor and Pipeline

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandDescriptor.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandRegistry.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandContext.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandPipeline.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/HelpCommand.java`
- Test: `core/src/test/java/com/javasleuth/command/BuiltinCommandSpecTest.java`

- [ ] **Step 1: Write integration test**

Create `BuiltinCommandSpecTest.java` with a small descriptor-level test:

```java
@Test
public void descriptorCanCarrySpecMeta() {
    CommandSpec spec = CommandSpec.builder("sample")
        .description("Sample command")
        .usage("sample [--flag]")
        .meta(CommandMeta.viewer(false, false))
        .option(OptionSpec.flag("flag").alias("--flag").build())
        .build();
    Command command = new Command() {
        @Override
        public String execute(String[] args) {
            return "ok";
        }

        @Override
        public String getDescription() {
            return "Sample command";
        }
    };

    CommandDescriptor descriptor = CommandDescriptor.ofSpec(spec, command);
    Assert.assertEquals("sample", descriptor.getName());
    Assert.assertSame(spec, descriptor.getSpec());
    Assert.assertSame(spec.getMeta(), descriptor.getMeta());
}
```

- [ ] **Step 2: Run failing integration test**

Run:

```bash
mvn -pl core -am -Dtest=BuiltinCommandSpecTest test
```

Expected: FAIL because `CommandDescriptor.ofSpec(...)` and `getSpec()` do not exist.

- [ ] **Step 3: Extend descriptor and registry entry**

Add `CommandSpec spec` to `CommandDescriptor` and `CommandRegistry.Entry`. Add `getSpec()` to both. Add factory:

```java
public static CommandDescriptor ofSpec(CommandSpec spec, Command command) {
    if (spec == null) {
        throw new IllegalArgumentException("spec is required");
    }
    return new CommandDescriptor(spec.getName(), command, spec.getMeta(), spec);
}
```

Keep the existing `of(String name, Command command, CommandMeta meta)` and set `spec` to `null` there.

- [ ] **Step 4: Add parsed command to context**

In `CommandContext`, add:

```java
private final ParsedCommand parsedCommand;

public ParsedCommand getParsedCommand() {
    return parsedCommand;
}

public CommandContext withParsedCommand(ParsedCommand parsed) {
    return new CommandContext(clientId, clientInfo, sessionId, connId, commandName,
        streaming, clientSession, cancellationToken, parsed);
}
```

Keep existing constructors by delegating with `parsedCommand=null`.

- [ ] **Step 5: Parse specs in pipeline**

In `CommandPipeline.executePrechecked(...)`, before creating `SyncInvocation`, add:

```java
CommandContext effectiveContext = context;
if (entry.getSpec() != null) {
    ParsedCommand parsed = CommandSpecParser.parse(entry.getSpec(), args);
    if (parsed.isHelpRequested()) {
        return new Result(true, CommandHelpRenderer.render(entry.getSpec()), null);
    }
    effectiveContext = context != null ? context.withParsedCommand(parsed) : context;
}
```

Use `effectiveContext` in `SyncInvocation`.

In `executeStreamPrechecked(...)`, use the same parse logic. If help is requested, call `sink.send(CommandHelpRenderer.render(entry.getSpec()))`, `sink.close("help")`, and return `StreamResult.ok()` before invoking the command.

- [ ] **Step 6: Update help command summary**

If `HelpCommand` can access `CommandRegistry.Entry` in a small change, prefer spec descriptions. If not, keep the existing map-based listing in this task and defer richer listing until command registration migration. Do not change public help output format except descriptions.

- [ ] **Step 7: Run integration tests**

Run:

```bash
mvn -pl core -am -Dtest=BuiltinCommandSpecTest,CommandPipelineStreamExecutionTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/javasleuth/core/command/CommandDescriptor.java \
  core/src/main/java/com/javasleuth/core/command/CommandRegistry.java \
  core/src/main/java/com/javasleuth/core/command/CommandContext.java \
  core/src/main/java/com/javasleuth/core/command/CommandPipeline.java \
  core/src/main/java/com/javasleuth/core/command/impl/HelpCommand.java \
  core/src/test/java/com/javasleuth/command/BuiltinCommandSpecTest.java
git commit -m "feat: route commands through optional specs"
```

## Task 9: Migrate Watch, Trace, and Monitor to CommandSpec

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java`
- Test: `core/src/test/java/com/javasleuth/command/BuiltinCommandSpecTest.java`
- Test: `core/src/test/java/com/javasleuth/command/CommandArgsValidationTest.java`
- Test: `core/src/test/java/com/javasleuth/core/command/impl/TraceCommandSampleOptionRemovedTest.java`

- [ ] **Step 1: Add built-in spec assertions**

Extend `BuiltinCommandSpecTest`:

```java
@Test
public void instrumentationCommandsExposeSpecs() {
    BuiltinCommandProvider provider = new BuiltinCommandProvider();
    Collection<CommandDescriptor> descriptors = provider.getCommandDescriptors(testProviderContext());
    assertHasSpec(descriptors, "watch");
    assertHasSpec(descriptors, "trace");
    assertHasSpec(descriptors, "monitor");
}

private static void assertHasSpec(Collection<CommandDescriptor> descriptors, String name) {
    for (CommandDescriptor d : descriptors) {
        if (name.equals(d.getName())) {
            Assert.assertNotNull(name + " spec", d.getSpec());
            return;
        }
    }
    Assert.fail("missing command: " + name);
}
```

Use existing test helpers from `BuiltinCommandCapabilityTest` for `testProviderContext()`.

- [ ] **Step 2: Add argument validation tests**

In `CommandArgsValidationTest`, add spec parser focused assertions:

```java
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
```

- [ ] **Step 3: Run failing migration tests**

Run:

```bash
mvn -pl core -am -Dtest=BuiltinCommandSpecTest,CommandArgsValidationTest,TraceCommandSampleOptionRemovedTest test
```

Expected: FAIL because the commands do not expose specs.

- [ ] **Step 4: Add static spec methods**

Add `public static CommandSpec spec()` to `WatchCommand`, `TraceCommand`, and `MonitorCommand`. Use the exact option ranges from the design spec. Example for monitor:

```java
public static CommandSpec spec() {
    return CommandSpec.builder("monitor")
        .description("Monitor method statistics periodically (simplified)")
        .usage("monitor <class-pattern> <method-pattern> [options]")
        .meta(CommandMeta.operator(false, true)
            .requiresBootstrap(BootstrapBridge.SPY_API)
            .withCapability(CommandCapability.LONG_RUNNING))
        .argument(ArgumentSpec.required("class-pattern"))
        .argument(ArgumentSpec.required("method-pattern"))
        .option(OptionSpec.longNumber("interval").alias("-i").alias("--interval").defaultValue(5000L).range(1L, 86400000L).build())
        .option(OptionSpec.integer("count").alias("-n").alias("--count").defaultValue(10).range(1, 100000).build())
        .option(OptionSpec.integer("limit").alias("--limit").defaultValue(50).range(1, 10000).build())
        .option(OptionSpec.flag("bg").alias("--bg").build())
        .example("monitor *Service* doWork -i 1000 -n 5")
        .build();
}
```

Add `implements SpecBackedCommand` and:

```java
@Override
public CommandSpec getSpec() {
    return spec();
}
```

- [ ] **Step 5: Use parsed command values**

At the start of each command parse method, read parsed command from context or parse fallback:

```java
private ParsedCommand parsedOrFallback(String[] args) {
    CommandContext ctx = CommandContextHolder.get();
    ParsedCommand parsed = ctx != null ? ctx.getParsedCommand() : null;
    return parsed != null ? parsed : CommandSpecParser.parse(spec(), args);
}
```

Replace manual loops with `ParsedCommand` getters. For `trace --sample` and `--sample-rate`, define removed options in the trace spec or check raw args before parser fallback so the existing removed-option message is preserved.

- [ ] **Step 6: Register descriptors from specs**

In `BuiltinCommandProvider`, register migrated commands with `CommandDescriptor.ofSpec(command.getSpec(), command)` instead of `add(...)` helper where practical. Preserve existing `instrumentationStreamMeta()` for non-migrated commands until they receive specs.

- [ ] **Step 7: Run migrated command tests**

Run:

```bash
mvn -pl core -am -Dtest=BuiltinCommandSpecTest,CommandArgsValidationTest,TraceCommandSampleOptionRemovedTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java \
  core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java \
  core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java \
  core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java \
  core/src/test/java/com/javasleuth/command/BuiltinCommandSpecTest.java \
  core/src/test/java/com/javasleuth/command/CommandArgsValidationTest.java \
  core/src/test/java/com/javasleuth/core/command/impl/TraceCommandSampleOptionRemovedTest.java
git commit -m "refactor: drive stream command parsing from specs"
```

## Task 10: Migrate VmTool to CommandSpec and Update Docs

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- Modify: `docs/usage/commands.md`
- Test: `core/src/test/java/com/javasleuth/command/BuiltinCommandSpecTest.java`
- Test: `core/src/test/java/com/javasleuth/command/CommandArgsValidationTest.java`

- [ ] **Step 1: Add vmtool spec tests**

Extend `BuiltinCommandSpecTest`:

```java
@Test
public void vmtoolExposesSubcommandSpec() {
    CommandSpec spec = VmToolCommand.spec();
    Assert.assertNotNull(spec.subcommand("track"));
    Assert.assertNotNull(spec.subcommand("invoke"));
    Assert.assertNotNull(spec.subcommand("invoke-static"));
    Assert.assertNotNull(spec.subcommand("invokestatic"));
    Assert.assertEquals(CommandMeta.ImpactLevel.MEDIUM, spec.getMeta().getImpactLevel());
}
```

Extend `CommandArgsValidationTest`:

```java
@Test
public void vmtoolInstancesRejectsInvalidLimit() {
    try {
        CommandSpecParser.parse(VmToolCommand.spec(), new String[] {"vmtool", "instances", "track-1", "--limit", "abc"});
        Assert.fail("expected invalid integer");
    } catch (CommandSpecParseException expected) {
        Assert.assertEquals("E_ARGS_INVALID", expected.getCode());
    }
}
```

- [ ] **Step 2: Run failing vmtool tests**

Run:

```bash
mvn -pl core -am -Dtest=BuiltinCommandSpecTest,CommandArgsValidationTest test
```

Expected: FAIL because `VmToolCommand.spec()` does not exist.

- [ ] **Step 3: Add vmtool spec**

Add `public static CommandSpec spec()` to `VmToolCommand` with command-level metadata matching current registration:

```java
CommandMeta meta = CommandMeta.operator(false, false)
    .requiresBootstrap(BootstrapBridge.SPY_API)
    .withCapability(CommandCapability.LONG_RUNNING)
    .withImpact(CommandMeta.ImpactLevel.MEDIUM)
    .withRateLimit(10)
    .withSubcommandRole("invoke", UserRole.ADMIN)
    .withSubcommandRole("invoke-static", UserRole.ADMIN)
    .withSubcommandRole("invokestatic", UserRole.ADMIN);
```

Define subcommands:

```java
SubcommandSpec.builder("track")
    .argument(ArgumentSpec.required("class-pattern"))
    .option(OptionSpec.string("loader").alias("--loader").alias("--loader-id").alias("--loader-hash").build())
    .option(OptionSpec.flag("first").alias("--first").alias("--unsafe-first").build())
    .option(OptionSpec.flag("subclasses").alias("--subclasses").alias("--include-subclasses").build())
    .option(OptionSpec.integer("max").alias("--max").defaultValue(500).range(1, 100000).build())
    .option(OptionSpec.integer("class-limit").alias("--class-limit").defaultValue(50).range(1, 10000).build())
    .build();
```

Add subcommands for `stop`, `tracks`, `instances`, `inspect`, `invoke`, `invoke-static`, `invokestatic`, and `histogram` with the options currently supported in `VmToolCommand.getHelp()`.

- [ ] **Step 4: Use parsed values in vmtool handlers**

Add fallback parser:

```java
private ParsedCommand parsedOrFallback(String[] args) {
    CommandContext ctx = CommandContextHolder.get();
    ParsedCommand parsed = ctx != null ? ctx.getParsedCommand() : null;
    return parsed != null ? parsed : CommandSpecParser.parse(spec(), args);
}
```

In each handler, prefer parsed subcommand arguments and options. Keep existing method invocation conversion and confirmation flow. Preserve `--confirm` handling by running dangerous confirmation before reading normalized parsed values for invoke paths.

- [ ] **Step 5: Register vmtool by spec**

In `BuiltinCommandProvider`, instantiate `VmToolCommand vmtool = new VmToolCommand(...)` and register it with `CommandDescriptor.ofSpec(vmtool.getSpec(), vmtool)`.

- [ ] **Step 6: Update commands documentation**

In `docs/usage/commands.md`, update the vmtool section to list the same subcommands and options as `VmToolCommand.spec()`. Include `--confirm <token>` on invoke paths and keep the warning that invoke operations require elevated permissions when RBAC is enabled.

- [ ] **Step 7: Run vmtool tests**

Run:

```bash
mvn -pl core -am -Dtest=BuiltinCommandSpecTest,CommandArgsValidationTest,VmToolMethodInvokerTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java \
  core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java \
  docs/usage/commands.md \
  core/src/test/java/com/javasleuth/command/BuiltinCommandSpecTest.java \
  core/src/test/java/com/javasleuth/command/CommandArgsValidationTest.java
git commit -m "refactor: define vmtool command spec"
```

## Task 11: Final Verification and Cleanup

**Files:**
- Modify only files needed to fix failures found by verification.

- [ ] **Step 1: Run targeted test groups**

Run:

```bash
mvn -pl core -am -Dtest=CommandExecutionEngineIsolationTest,CommandPipelineStreamExecutionTest,JobManagerConcurrencyTest test
mvn -pl core -am -Dtest=ProductionConfigRuntimeValidationTest,SleuthConfigParserTest,ConfigSemanticsTest,DefaultConfigConsistencyTest test
mvn -pl agent,core -am -Dtest=CrossClassLoaderFacadeContractTest,CrossClassLoaderReflectionContractTest,AgentLifecycleTest test
mvn -pl core -am -Dtest=CommandSpecParserTest,CommandHelpRendererTest,BuiltinCommandSpecTest,CommandArgsValidationTest,TraceCommandSampleOptionRemovedTest test
```

Expected: every command exits with status `0`.

- [ ] **Step 2: Run raw config search**

Run:

```bash
rg -n "config\.get(Int|Boolean|Long|Double)" core/src/main/java/com/javasleuth/core/{command,enhancement}
```

Expected: no raw reads in the target files listed in Task 5.

- [ ] **Step 3: Run full module verification**

Run:

```bash
mvn -pl core -am test
mvn -pl agent -am test
```

Expected: both commands exit with status `0`.

- [ ] **Step 4: Run full repository test if time permits**

Run:

```bash
mvn test
```

Expected: exit status `0`. If this is too slow for the session, record the exact targeted verification commands and their successful outputs in the final response.

- [ ] **Step 5: Commit verification fixes**

If Step 1 through Step 4 required fixes, commit them:

```bash
git add <fixed-files>
git commit -m "fix: stabilize runtime command hardening"
```

If no fixes were required, do not create an empty commit.

## Self-Review

- Spec coverage: Tasks 1 through 3 cover execution pools and cancellation; Tasks 4 and 5 cover schema config; Task 6 covers cross-ClassLoader contract versioning; Tasks 7 through 10 cover `CommandSpec`; Task 11 covers verification.
- Draft marker scan: no incomplete sections are intended in this plan.
- Type consistency: `CancellationToken`, `CancellationTokenSource`, `ConfigValidationResult`, `VmToolConfig`, `SpecBackedCommand`, `CommandSpec`, `ParsedCommand`, and `CommandSpecParseException` names match across tasks.
- Execution order: each task can be reviewed and committed independently, and later tasks build on APIs introduced by earlier tasks.
