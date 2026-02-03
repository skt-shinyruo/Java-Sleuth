package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.DangerousCommandConfirmationManager;
import com.javasleuth.security.InputValidator;
import com.javasleuth.util.PerformanceOptimizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final Executor commandExecutor;

    public CommandPipeline(InputValidator inputValidator, AuthorizationManager authorizationManager, ProductionConfig config) {
        this.inputValidator = inputValidator;
        this.authorizationManager = authorizationManager;
        this.config = config;
        this.dangerousConfirm = DangerousCommandConfirmationManager.getInstance();
        this.commandExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sleuth-cmd-exec");
            t.setDaemon(true);
            return t;
        });
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
        String result;
        long timeoutMs = config.getCommandTimeout();
        boolean cacheable = entry.getMeta().isCacheable() && isSafeToCache(commandName, args);
        if (cacheable) {
            String clientKey = context != null && context.getClientId() != null ? context.getClientId() : "unknown";
            String cacheKey = clientKey + ":" + commandName + ":" + String.join(":", args);
            try {
                result = PerformanceOptimizer.getCachedResult(cacheKey, () -> {
                    try {
                        return executeWithTimeout(command, args, timeoutMs);
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
                result = executeWithTimeout(command, args, timeoutMs);
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
        // Avoid caching state-changing subcommands.
        if ("sysprop".equals(commandName) && args != null && args.length > 1) {
            if ("set".equalsIgnoreCase(args[1])) {
                return false;
            }
        }
        return true;
    }

    private String executeWithTimeout(Command command, String[] args, long timeoutMs) throws Exception {
        if (timeoutMs <= 0) {
            return command.execute(args);
        }

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return command.execute(args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, commandExecutor);

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
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
