package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.DangerousCommandConfirmationManager;
import com.javasleuth.security.InputValidator;
import com.javasleuth.util.PerformanceOptimizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandPipeline {
    public static class PrecheckResult {
        private final boolean ok;
        private final String error;
        private final String[] args;

        private PrecheckResult(boolean ok, String error, String[] args) {
            this.ok = ok;
            this.error = error;
            this.args = args;
        }

        public static PrecheckResult ok(String[] args) {
            return new PrecheckResult(true, null, args);
        }

        public static PrecheckResult denied(String error) {
            return new PrecheckResult(false, error, null);
        }

        public static PrecheckResult denied(String error, String[] args) {
            return new PrecheckResult(false, error, args);
        }

        public boolean isOk() {
            return ok;
        }

        public String getError() {
            return error;
        }

        public String[] getArgs() {
            return args;
        }
    }

    public static class Result {
        private final boolean success;
        private final String output;
        private final String error;

        public Result(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getError() {
            return error;
        }
    }

    private final InputValidator inputValidator;
    private final AuthorizationManager authorizationManager;
    private final DangerousCommandConfirmationManager dangerousConfirm;
    private final ProductionConfig config;

    // Dedicated executor for command execution (avoid ForkJoinPool contention).
    private final ThreadPoolExecutor commandExecutor;

    private static final Object HIGH_IMPACT_LOCK = new Object();
    private static volatile Semaphore HIGH_IMPACT_SEMAPHORE;
    private static volatile int HIGH_IMPACT_LIMIT = -1;

    public CommandPipeline(InputValidator inputValidator, AuthorizationManager authorizationManager, ProductionConfig config) {
        this.inputValidator = inputValidator;
        this.authorizationManager = authorizationManager;
        this.config = config;
        this.dangerousConfirm = DangerousCommandConfirmationManager.getInstance();
        final int coreSize = config.getCommandExecutorCoreSize();
        final int maxSize = config.getCommandExecutorMaxSize();
        final int queueCapacity = config.getCommandExecutorQueueCapacity();

        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);
        AtomicLong threadSeq = new AtomicLong(0);
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
            coreSize,
            Math.max(coreSize, maxSize),
            60L,
            TimeUnit.SECONDS,
            queue,
            r -> {
                Thread t = new Thread(r, "sleuth-cmd-exec-" + threadSeq.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        tpe.allowCoreThreadTimeOut(true);
        this.commandExecutor = tpe;
    }

    public Result execute(CommandRegistry.Entry entry, String[] args, CommandContext context) {
        String commandName = args[0].toLowerCase();
        PrecheckResult precheck = precheck(entry, commandName, args, context);
        if (!precheck.isOk()) {
            return new Result(false, null, precheck.getError());
        }
        return executePrechecked(entry, precheck.getArgs(), context);
    }

    public Result executePrechecked(CommandRegistry.Entry entry, String[] args, CommandContext context) {
        if (args == null || args.length == 0) {
            return new Result(false, null, "Empty command");
        }
        String commandName = args[0].toLowerCase();

        Command command = entry.getCommand();
        CommandMeta meta = entry.getMeta();
        String result;
        long timeoutMs = config.getCommandTimeout();
        boolean cacheable = meta != null && meta.isCacheable() && isSafeToCache(commandName, args);
        if (cacheable) {
            String clientKey = context != null && context.getClientId() != null ? context.getClientId() : "unknown";
            String cacheKey = clientKey + ":" + commandName + ":" + String.join(":", args);
            try {
                result = PerformanceOptimizer.getCachedResult(cacheKey, () -> {
                    ImpactPermit permit;
                    try {
                        permit = acquireImpactPermit(meta);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    try {
                        return executeWithTimeout(command, args, timeoutMs, permit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (RuntimeException e) {
                Throwable cause = e.getCause();
                String message = cause != null ? cause.getMessage() : e.getMessage();
                return new Result(false, null, message != null ? message : "Command execution failed");
            }
        } else {
            try {
                ImpactPermit permit = acquireImpactPermit(meta);
                result = executeWithTimeout(command, args, timeoutMs, permit);
            } catch (Exception e) {
                return new Result(false, null, e.getMessage());
            }
        }

        InputValidator.ValidationResult outputValidation = inputValidator.sanitizeOutput(result);
        String sanitized = outputValidation.getSanitizedOutput() != null ? outputValidation.getSanitizedOutput() : result;
        return new Result(true, sanitized, null);
    }

    private boolean isSafeToCache(String commandName, String[] args) {
        if (commandName == null) {
            return false;
        }
        // Avoid caching context-sensitive commands.
        if ("session".equals(commandName)) {
            return false;
        }
        // dashboard realtime explicitly requests no caching (still allow cached default dashboard).
        if ("dashboard".equals(commandName) && args != null) {
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a == null) {
                    continue;
                }
                if ("realtime".equalsIgnoreCase(a.trim())) {
                    return false;
                }
            }
        }
        // Avoid caching state-changing subcommands.
        if ("sysprop".equals(commandName) && args != null && args.length > 1) {
            if ("set".equalsIgnoreCase(args[1])) {
                return false;
            }
        }
        return true;
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

    private String executeWithTimeout(Command command, String[] args, long timeoutMs, ImpactPermit permit) throws Exception {
        if (timeoutMs <= 0) {
            try {
                return command.execute(args);
            } finally {
                if (permit != null) {
                    permit.release();
                }
            }
        }

        PermitFutureTask task = new PermitFutureTask(permit, () -> command.execute(args));
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
                // ignore
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
                // ignore
            }
            if (removed && permit != null) {
                permit.release();
            }
            throw new Exception("Command interrupted");
        }
    }

    public String validateAndAuthorize(CommandRegistry.Entry entry, String commandName, String[] args, CommandContext context) {
        PrecheckResult pre = precheck(entry, commandName, args, context);
        return pre.isOk() ? null : pre.getError();
    }

    public PrecheckResult precheck(CommandRegistry.Entry entry, String commandName, String[] args, CommandContext context) {
        String[] argsForChecks = stripConfirmArgs(args);
        InputValidator.ValidationResult validation =
            inputValidator.validateCommand(context.getClientId(), context.getClientInfo(), commandName, argsForChecks);
        if (!validation.isValid()) {
            return PrecheckResult.denied("Security validation failed: " + validation.getMessage(), argsForChecks);
        }

        if (config.isAuthorizationEnabled() && !"auth".equals(commandName)) {
            AuthorizationManager.AuthorizationResult authz =
                authorizationManager.authorize(context.getSessionId(), commandName, argsForChecks, entry != null ? entry.getMeta() : null);
            if (!authz.isAllowed()) {
                return PrecheckResult.denied(authz.getReason(), argsForChecks);
            }
        }

        DangerousCommandConfirmationManager.ConfirmationResult confirm =
            dangerousConfirm.confirmIfRequired(
                context.getSessionId(),
                context.getClientInfo(),
                commandName,
                args,
                entry != null ? entry.getMeta() : null
            );
        if (!confirm.isAllowed()) {
            return PrecheckResult.denied(confirm.getError(), confirm.getNormalizedArgs() != null ? confirm.getNormalizedArgs() : args);
        }

        return PrecheckResult.ok(confirm.getNormalizedArgs());
    }

    private static String[] stripConfirmArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }

        List<String> out = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) {
                continue;
            }
            String t = a.trim();
            if (t.isEmpty()) {
                continue;
            }

            if ("--confirm".equalsIgnoreCase(t) || "-confirm".equalsIgnoreCase(t)) {
                if (i + 1 < args.length) {
                    i++;
                }
                continue;
            }
            if (t.toLowerCase().startsWith("--confirm=")) {
                continue;
            }
            out.add(t);
        }
        return out.toArray(new String[0]);
    }
}
