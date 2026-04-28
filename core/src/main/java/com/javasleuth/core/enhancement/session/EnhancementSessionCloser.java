package com.javasleuth.core.enhancement.session;

@FunctionalInterface
public interface EnhancementSessionCloser {
    void close(String reason) throws Exception;
}
