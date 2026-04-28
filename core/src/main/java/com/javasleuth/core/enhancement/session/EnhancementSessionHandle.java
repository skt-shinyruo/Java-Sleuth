package com.javasleuth.core.enhancement.session;

public interface EnhancementSessionHandle extends AutoCloseable {
    String getSessionId();

    EnhancementSessionKind getKind();

    boolean isClosed();

    void close(String reason);

    @Override
    default void close() {
        close("close");
    }
}
