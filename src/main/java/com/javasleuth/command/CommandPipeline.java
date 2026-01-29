package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.InputValidator;
import com.javasleuth.util.PerformanceOptimizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CommandPipeline {
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
    private final ProductionConfig config;

    public CommandPipeline(InputValidator inputValidator, AuthorizationManager authorizationManager, ProductionConfig config) {
        this.inputValidator = inputValidator;
        this.authorizationManager = authorizationManager;
        this.config = config;
    }

    public Result execute(CommandRegistry.Entry entry, String[] args, CommandContext context) {
        String commandName = args[0].toLowerCase();

        String validationError = validateAndAuthorize(commandName, args, context);
        if (validationError != null) {
            return new Result(false, null, validationError);
        }

        Command command = entry.getCommand();
        String result;
        long timeoutMs = config.getCommandTimeout();
        boolean cacheable = entry.getMeta().isCacheable() && isSafeToCache(commandName, args);
        if (cacheable) {
            String cacheKey = commandName + ":" + String.join(":", args);
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
        });

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

    public String validateAndAuthorize(String commandName, String[] args, CommandContext context) {
        InputValidator.ValidationResult validation =
            inputValidator.validateCommand(context.getClientId(), context.getClientInfo(), commandName, args);
        if (!validation.isValid()) {
            return "Security validation failed: " + validation.getMessage();
        }

        if (config.isAuthorizationEnabled() && !"auth".equals(commandName)) {
            AuthorizationManager.AuthorizationResult authz =
                authorizationManager.authorize(context.getSessionId(), commandName, args);
            if (!authz.isAllowed()) {
                return authz.getReason();
            }
        }
        return null;
    }
}
