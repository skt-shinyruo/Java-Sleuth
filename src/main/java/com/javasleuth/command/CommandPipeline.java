package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.InputValidator;
import com.javasleuth.util.PerformanceOptimizer;

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
        if (entry.getMeta().isCacheable()) {
            String cacheKey = commandName + ":" + String.join(":", args);
            result = PerformanceOptimizer.getCachedResult(cacheKey, () -> {
                try {
                    return command.execute(args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            try {
                result = command.execute(args);
            } catch (Exception e) {
                return new Result(false, null, e.getMessage());
            }
        }

        InputValidator.ValidationResult outputValidation = inputValidator.sanitizeOutput(result);
        String sanitized = outputValidation.getSanitizedOutput() != null ? outputValidation.getSanitizedOutput() : result;
        return new Result(true, sanitized, null);
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
