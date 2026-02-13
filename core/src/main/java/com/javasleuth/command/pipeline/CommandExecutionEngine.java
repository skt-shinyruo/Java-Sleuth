package com.javasleuth.command.pipeline;

import com.javasleuth.command.Command;
import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.command.StreamCommand;
import com.javasleuth.command.StreamSink;
import com.javasleuth.command.session.ClientDisconnectedException;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.util.SleuthExecutors;
import com.javasleuth.util.SleuthLogContext;
import com.javasleuth.util.SleuthThreadFactory;
import com.javasleuth.security.CommandMeta;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 命令执行引擎：统一 timeout/executor/impact permit，供 Pipeline 使用。
 *
 * <p>该类不处理 precheck（validate/authz/confirm），只负责“已允许执行”的命令执行。</p>
 */
public final class CommandExecutionEngine {
    private final ProductionConfig config;
    private final ThreadPoolExecutor commandExecutor;

    private static final Object HIGH_IMPACT_LOCK = new Object();
    private static volatile Semaphore HIGH_IMPACT_SEMAPHORE;
    private static volatile int HIGH_IMPACT_LIMIT = -1;

    public CommandExecutionEngine(ProductionConfig config) {
        this.config = config;
        final int coreSize = config.getCommandExecutorCoreSize();
        final int maxSize = config.getCommandExecutorMaxSize();
        final int queueCapacity = config.getCommandExecutorQueueCapacity();

        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
            coreSize,
            Math.max(coreSize, maxSize),
            60L,
            TimeUnit.SECONDS,
            queue,
            SleuthThreadFactory.daemon("sleuth-cmd-exec"),
            new ThreadPoolExecutor.AbortPolicy()
        );
        tpe.allowCoreThreadTimeOut(true);
        this.commandExecutor = tpe;
    }

    public void shutdown() {
        SleuthExecutors.shutdownAndAwait(commandExecutor, "command-exec", 5, TimeUnit.SECONDS);
    }

    public String executeSync(Command command, String[] args, CommandMeta meta, long timeoutMs, CommandContext context) throws Exception {
        ImpactPermit permit = acquireImpactPermit(meta);
        return executeWithTimeout(command, args, timeoutMs, permit, context);
    }

    public void executeStream(StreamCommand command, String[] args, CommandMeta meta, long timeoutMs, StreamSink sink, CommandContext context) throws Exception {
        ImpactPermit permit = acquireImpactPermit(meta);
        executeStreamWithTimeout(command, args, timeoutMs, permit, sink, context);
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

        int limit = config.getHighImpactConcurrentLimit();
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
            commandExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            if (permit != null) {
                permit.release();
            }
            throw new Exception("Server is busy: command execution queue is full");
        }

        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            boolean removed = false;
            try {
                removed = commandExecutor.remove(task);
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
                removed = commandExecutor.remove(task);
            } catch (Exception ignore) {
                // 忽略
            }
            if (removed && permit != null) {
                permit.release();
            }
            throw new Exception("Command interrupted");
        }
    }

    private void executeStreamWithTimeout(StreamCommand command, String[] args, long timeoutMs, ImpactPermit permit, StreamSink sink, CommandContext context) throws Exception {
        if (timeoutMs <= 0) {
            try {
                applyContext(context);
                command.executeStream(args, sink);
            } finally {
                clearContext();
                if (permit != null) {
                    permit.release();
                }
            }
            return;
        }

        PermitFutureTaskVoid task = new PermitFutureTaskVoid(permit, () -> {
            applyContext(context);
            try {
                command.executeStream(args, sink);
            } finally {
                clearContext();
            }
            return null;
        });
        try {
            commandExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            if (permit != null) {
                permit.release();
            }
            throw new Exception("Server is busy: command execution queue is full");
        }

        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            boolean removed = false;
            try {
                removed = commandExecutor.remove(task);
            } catch (Exception ignore) {
                // 忽略
            }
            if (removed && permit != null) {
                // Task won't run: PermitFutureTaskVoid.run() won't execute, release here.
                permit.release();
            }
            throw new Exception("Command timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof ClientDisconnectedException) {
                throw (ClientDisconnectedException) cause;
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
                removed = commandExecutor.remove(task);
            } catch (Exception ignore) {
                // 忽略
            }
            if (removed && permit != null) {
                permit.release();
            }
            throw new Exception("Command interrupted");
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
