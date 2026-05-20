package com.javasleuth.core.command.spi;

import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.security.AuditLogger;

/**
 * Restricted attach-scope context exposed to external command plugins.
 *
 * <p>The public plugin SPI is intentionally limited to stable, read-only support services. Core-owned
 * objects such as Instrumentation, transformers, spy dispatchers, auth managers, job registries, and
 * session registries are not exposed through this type.</p>
 */
public final class RestrictedCommandProviderContext {
    private final ConfigView config;
    private final AuditLogger auditLogger;

    public RestrictedCommandProviderContext(ConfigView config, AuditLogger auditLogger) {
        this.config = config;
        this.auditLogger = auditLogger;
    }

    public ConfigView getConfig() {
        return config;
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }
}
