package com.javasleuth.core.command.pipeline;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.CancellationTokenSource;
import com.javasleuth.core.command.StreamCommand;
import com.javasleuth.core.command.StreamSink;
import com.javasleuth.core.command.session.ClientDisconnectedException;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.PerformanceConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import com.javasleuth.foundation.util.SleuthExecutors;
import com.javasleuth.foundation.util.SleuthLogContext;
import com.javasleuth.foundation.util.SleuthThreadFactory;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 命令执行引擎：统一 timeout/executor/impact permit，供 Pipeline 使用。
 *
 * <p>该类不处理 precheck（validate/authz/confirm），只负责“已允许执行”的命令执行。</p>
 */
public final class CommandExecutionEngine {
    private final ProductionConfig config;
    private final ThreadPoolExecutor shortCommandExecutor;
    private final ThreadPoolExecutor streamCommandExecutor;

    private static final Object HIGH_IMPACT_LOCK = new Object();
    private static volatile Semaphore HIGH_IMPACT_SEMAPHORE;
    private static volatile int HIGH_IMPACT_LIMIT = -1;

    public CommandExecutionEngine(ProductionConfig config) {
        this.config = config;
        SleuthConfig typed = SleuthConfigParser.parse(config.snapshot());
        PerformanceConfig perf = typed.performance();
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
    }

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

    public void shutdown() {
        SleuthExecutors.shutdownAndAwait(shortCommandExecutor, "short-command-exec", 5, TimeUnit.SECONDS);
        SleuthExecutors.shutdownAndAwait(streamCommandExecutor, "stream-command-exec", 5, TimeUnit.SECONDS);
    }

    public String executeSync(Command command, String[] args, CommandMeta meta, long timeoutMs, CommandContext context) throws Exception {
        ImpactPermit permit = acquireImpactPermit(meta);
        return executeWithTimeout(command, args, timeoutMs, permit, context);
    }

    public StreamExecutionHandle executeStream(StreamCommand command, String[] args, CommandMeta meta, long timeoutMs, StreamSink sink, CommandContext context) throws Exception {
        ImpactPermit permit = acquireImpactPermit(meta);
        return executeStreamWithTimeout(command, args, timeoutMs, permit, sink, context);
    }

    private static final class ImpactPermit {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private ImpactPermit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        static ImpactPermit none() {
            return new ImpactPermit(null);
        }

        void release() {
            if (semaphore == null) {
                return;
            }
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }

    private static final class PermitFutureTask extends FutureTask<String> {
        private final ImpactPermit permit;

        private PermitFutureTask(ImpactPermit permit, java.util.concurrent.Callable<String> callable) {
            super(callable);
            this.permit = permit;
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                if (permit != null) {
                    permit.release();
                }
            }
        }
    }

    private static final class PermitFutureTaskVoid extends FutureTask<Void> {
        private final ImpactPermit permit;

        private PermitFutureTaskVoid(ImpactPermit permit, java.util.concurrent.Callable<Void> callable) {
            super(callable);
            this.permit = permit;
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                if (permit != null) {
                    permit.release();
                }
            }
        }
    }

    private ImpactPermit acquireImpactPermit(CommandMeta meta) throws Exception {
        if (meta == null || meta.getImpactLevel() != CommandMeta.ImpactLevel.HIGH) {
            return ImpactPermit.none();
        }

        int limit = SleuthConfigSchema.SECURITY_IMPACT_HIGH_CONCURRENT_LIMIT.read(config);
        if (limit <= 0) {
            return ImpactPermit.none();
        }

        Semaphore sem = getOrCreateHighImpactSemaphore(limit);
        if (sem == null) {
            return ImpactPermit.none();
        }

        if (!sem.tryAcquire()) {
            throw new Exception("High impact command is already running; please retry later");
        }

        return new ImpactPermit(sem);
    }

    private Semaphore getOrCreateHighImpactSemaphore(int limit) {
        if (limit <= 0) {
            return null;
        }
        Semaphore existing = HIGH_IMPACT_SEMAPHORE;
        if (existing != null && HIGH_IMPACT_LIMIT == limit) {
            return existing;
        }
        synchronized (HIGH_IMPACT_LOCK) {
            if (HIGH_IMPACT_SEMAPHORE == null || HIGH_IMPACT_LIMIT != limit) {
                HIGH_IMPACT_SEMAPHORE = new Semaphore(limit, true);
                HIGH_IMPACT_LIMIT = limit;
            }
            return HIGH_IMPACT_SEMAPHORE;
        }
    }

    private String executeWithTimeout(Command command, String[] args, long timeoutMs, ImpactPermit permit, CommandContext context) throws Exception {
        if (timeoutMs <= 0) {
            try {
                applyContext(context);
                return command.execute(args);
            } finally {
                clearContext();
                if (permit != null) {
                    permit.release();
                }
            }
        }

        PermitFutureTask task = new PermitFutureTask(permit, () -> {
            applyContext(context);
            try {
                return command.execute(args);
            } finally {
                clearContext();
            }
        });
        try {
            shortCommandExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            if (permit != null) {
                permit.release();
            }
            throw new Exception("Server is busy: short command execution queue is full");
        }

        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            boolean removed = false;
            try {
                removed = shortCommandExecutor.remove(task);
            } catch (Exception ignore) {
                // 忽略
            }
            if (removed && permit != null) {
                // Task won't run: PermitFutureTask.run() won't execute, release here.
                permit.release();
            }
            throw new Exception("Command timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new Exception(cause != null ? cause.getMessage() : "Command execution failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            boolean removed = false;
            try {
                removed = shortCommandExecutor.remove(task);
            } catch (Exception ignore) {
                // 忽略
            }
            if (removed && permit != null) {
                permit.release();
            }
            throw new Exception("Command interrupted");
        }
    }

    private StreamExecutionHandle executeStreamWithTimeout(StreamCommand command, String[] args, long timeoutMs, ImpactPermit permit, StreamSink sink, CommandContext context) throws Exception {
        CancellationTokenSource source = new CancellationTokenSource();
        StreamStartup startup = new StreamStartup();
        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicReference<StreamCompletion> completion = new AtomicReference<StreamCompletion>();
        PermitFutureTaskVoid task = new PermitFutureTaskVoid(permit, () -> {
            return executeStreamTask(command, args, sink, context, source, startup, completionLatch, completion);
        });
        StreamExecutionHandle handle = new StreamExecutionHandle(task, source, startup.started, completionLatch, completion);
        try {
            streamCommandExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            if (permit != null) {
                permit.release();
            }
            throw new Exception("Server is busy: stream command execution queue is full");
        }

        try {
            startup.await(currentStreamStartupTimeoutMs());
            return handle;
        } catch (TimeoutException e) {
            source.cancel();
            task.cancel(true);
            boolean removed = false;
            try {
                removed = streamCommandExecutor.remove(task);
            } catch (Exception ignore) {
                // 忽略
            }
            if (removed && permit != null) {
                // Task won't run: PermitFutureTaskVoid.run() won't execute, release here.
                permit.release();
            }
            handle.complete(StreamCompletion.cancelled("startup timeout"));
            throw new Exception("Stream command startup timed out after " + currentStreamStartupTimeoutMs() + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            source.cancel();
            task.cancel(true);
            boolean removed = false;
            try {
                removed = streamCommandExecutor.remove(task);
            } catch (Exception ignore) {
                // 忽略
            }
            if (removed && permit != null) {
                permit.release();
            }
            handle.complete(StreamCompletion.cancelled("startup interrupted"));
            throw new Exception("Command interrupted");
        } catch (Exception e) {
            throw e;
        }
    }

    private Void executeStreamTask(
        StreamCommand command,
        String[] args,
        StreamSink sink,
        CommandContext context,
        CancellationTokenSource source,
        StreamStartup startup,
        CountDownLatch completionLatch,
        AtomicReference<StreamCompletion> completion
    ) throws Exception {
        boolean closeOnExit = false;
        try {
            applyContext(context != null ? context.withCancellationToken(source.token()) : null);
            startup.started();
            command.executeStream(args, sink);
            closeOnExit = true;
            Throwable closeFailure = closeStreamSink(sink, null);
            if (closeFailure != null) {
                source.cancel();
                complete(completion, completionLatch, StreamCompletion.failed(closeFailure));
                throw asException(closeFailure);
            } else if (source.token().isCancelled()) {
                complete(completion, completionLatch, StreamCompletion.cancelled("cancelled"));
            } else {
                complete(completion, completionLatch, StreamCompletion.success());
            }
            return null;
        } catch (ClientDisconnectedException e) {
            source.cancel();
            startup.failed(e);
            complete(completion, completionLatch, StreamCompletion.failed(e));
            throw e;
        } catch (Throwable t) {
            startup.failed(t);
            if (source.token().isCancelled() || Thread.currentThread().isInterrupted()) {
                closeStreamSink(sink, "cancelled");
                complete(completion, completionLatch, StreamCompletion.cancelled("cancelled"));
                return null;
            }
            errorStreamSink(sink, t);
            complete(completion, completionLatch, StreamCompletion.failed(t));
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new Exception(t);
        } finally {
            clearContext();
            if (!closeOnExit && completion.get() == null) {
                Throwable closeFailure = closeStreamSink(sink, null);
                if (closeFailure != null) {
                    complete(completion, completionLatch, StreamCompletion.failed(closeFailure));
                } else {
                    complete(completion, completionLatch, StreamCompletion.success());
                }
            }
        }
    }

    private long currentStreamStartupTimeoutMs() {
        return SleuthConfigSchema.PERFORMANCE_COMMAND_STREAM_STARTUP_TIMEOUT_MS.read(config);
    }

    private static void complete(
        AtomicReference<StreamCompletion> completion,
        CountDownLatch completionLatch,
        StreamCompletion result
    ) {
        if (completion.compareAndSet(null, result)) {
            completionLatch.countDown();
        }
    }

    private static Throwable closeStreamSink(StreamSink sink, String summary) {
        if (sink == null) {
            return null;
        }
        try {
            sink.close(summary);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static void errorStreamSink(StreamSink sink, Throwable t) {
        if (sink == null) {
            return;
        }
        String message = t != null && t.getMessage() != null ? t.getMessage() : "Stream command failed";
        try {
            sink.error(message);
        } catch (Throwable ignore) {
            // The original stream failure remains the lifecycle result.
        }
    }

    private static Exception asException(Throwable t) {
        if (t instanceof Exception) {
            return (Exception) t;
        }
        return new Exception(t);
    }

    private static final class StreamStartup {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean signaled = new AtomicBoolean(false);
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        void started() {
            started.set(true);
            if (signaled.compareAndSet(false, true)) {
                latch.countDown();
            }
        }

        void failed(Throwable t) {
            failure.compareAndSet(null, t);
            if (signaled.compareAndSet(false, true)) {
                latch.countDown();
            }
        }

        void await(long timeoutMs) throws Exception {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("stream startup timeout");
            }
            Throwable t = failure.get();
            if (t == null) {
                return;
            }
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new Exception(t);
        }
    }

    private static void applyContext(CommandContext context) {
        if (context == null) {
            return;
        }
        try {
            CommandContextHolder.set(context);
        } catch (Exception ignore) {
            // 忽略
        }
        try {
            SleuthLogContext.setConnection(context.getClientId(), context.getSessionId(), context.getConnId());
            SleuthLogContext.setCommand(context.getCommandName());
        } catch (Exception ignore) {
            // 忽略
        }
    }

    private static void clearContext() {
        try {
            CommandContextHolder.clear();
        } catch (Exception ignore) {
            // 忽略
        }
        try {
            SleuthLogContext.clear();
        } catch (Exception ignore) {
            // 忽略
        }
    }
}
