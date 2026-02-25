package com.javasleuth.core.enhancement;

/**
 * Marker interface for enhancers that inject calls to bootstrap-side classes.
 *
 * <p>When running in the real agent runtime, these injected classes must be visible from
 * {@code BootstrapClassLoader} (otherwise the enhanced application bytecode may crash at runtime).</p>
 */
public interface BootstrapDependentEnhancer extends ClassEnhancer {
    /**
     * A representative bootstrap-side class that must be visible for this enhancer to be safe to enable.
     *
     * <p>Use a binary class name, e.g. {@code com.javasleuth.bootstrap.monitor.TraceInterceptor}.</p>
     */
    String requiredBootstrapClassName();
}

