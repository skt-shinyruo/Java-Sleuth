package com.javasleuth.core.command.pipeline;

import com.javasleuth.core.command.StreamSink;
import com.javasleuth.foundation.security.InputValidator;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GuardedStreamSink implements StreamSink {
    private final StreamSink delegate;
    private final InputValidator validator;
    private final AtomicBoolean ended = new AtomicBoolean(false);

    public GuardedStreamSink(StreamSink delegate, InputValidator validator) {
        this.delegate = delegate;
        this.validator = validator;
    }

    @Override
    public void send(String data) {
        if (delegate == null || ended.get()) {
            return;
        }
        delegate.send(sanitize(data));
    }

    @Override
    public void close(String summary) {
        if (delegate == null) {
            return;
        }
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        delegate.close(sanitize(summary));
    }

    @Override
    public void error(String message) {
        if (delegate == null) {
            return;
        }
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        delegate.error(sanitize(message));
    }

    public void ensureClosed() {
        if (delegate == null) {
            return;
        }
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        delegate.close(null);
    }

    private String sanitize(String raw) {
        if (validator == null) {
            return raw == null ? "" : raw;
        }
        InputValidator.ValidationResult r = validator.sanitizeOutput(raw);
        String out = r != null ? r.getSanitizedOutput() : null;
        return out != null ? out : (raw == null ? "" : raw);
    }
}
