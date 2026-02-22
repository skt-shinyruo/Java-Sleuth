package com.javasleuth.core.command.pipeline;

import com.javasleuth.foundation.security.InputValidator;

public final class OutputSanitizeInterceptor implements PipelineInterceptor<SyncInvocation, String> {
    private final InputValidator inputValidator;

    public OutputSanitizeInterceptor(InputValidator inputValidator) {
        this.inputValidator = inputValidator;
    }

    @Override
    public String intercept(SyncInvocation ctx, PipelineHandler<SyncInvocation, String> next) throws Exception {
        String raw = next.handle(ctx);
        if (raw == null) {
            return "";
        }
        if (inputValidator == null) {
            return raw;
        }
        InputValidator.ValidationResult outputValidation = inputValidator.sanitizeOutput(raw);
        String sanitized = outputValidation != null ? outputValidation.getSanitizedOutput() : null;
        return sanitized != null ? sanitized : raw;
    }
}
