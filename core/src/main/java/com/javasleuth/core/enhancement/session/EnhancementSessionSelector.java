package com.javasleuth.core.enhancement.session;

public interface EnhancementSessionSelector {
    boolean matches(EnhancementSessionSnapshot snapshot);
}
