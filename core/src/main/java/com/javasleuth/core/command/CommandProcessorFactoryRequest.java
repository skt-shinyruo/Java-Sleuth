package com.javasleuth.core.command;

import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;

/**
 * Explicit composition input for {@link CommandProcessorFactory}.
 *
 * <p>This replaces long positional argument lists with a single attach-scope request object.</p>
 */
public final class CommandProcessorFactoryRequest {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final Runnable shutdownHook;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final AuthenticationManager authenticationManager;
    private final AuthorizationManager authorizationManager;
    private final DangerousCommandConfirmationManager dangerousConfirm;
    private final ClientSessionRegistry clientSessionRegistry;
    private final MetricsCollector metricsCollector;
    private final JobManager jobManager;
    private final VmToolSessionRegistry vmToolSessionRegistry;
    private final PerformanceOptimizer performanceOptimizer;
    private final SleuthSpyDispatcher spyDispatcher;
    private final EnhancementSessionRegistry enhancementSessionRegistry;

    private CommandProcessorFactoryRequest(Builder builder) {
        this.instrumentation = builder.instrumentation;
        this.transformer = builder.transformer;
        this.shutdownHook = builder.shutdownHook;
        this.config = builder.config;
        this.auditLogger = builder.auditLogger;
        this.authenticationManager = builder.authenticationManager;
        this.authorizationManager = builder.authorizationManager;
        this.dangerousConfirm = builder.dangerousConfirm;
        this.clientSessionRegistry = builder.clientSessionRegistry;
        this.metricsCollector = builder.metricsCollector;
        this.jobManager = builder.jobManager;
        this.vmToolSessionRegistry = builder.vmToolSessionRegistry;
        this.performanceOptimizer = builder.performanceOptimizer;
        this.spyDispatcher = builder.spyDispatcher;
        this.enhancementSessionRegistry = builder.enhancementSessionRegistry;
    }

    public static Builder builder(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        return new Builder(instrumentation, transformer);
    }

    public Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public SleuthClassFileTransformer getTransformer() {
        return transformer;
    }

    public Runnable getShutdownHook() {
        return shutdownHook;
    }

    public ProductionConfig getConfig() {
        return config;
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    public AuthenticationManager getAuthenticationManager() {
        return authenticationManager;
    }

    public AuthorizationManager getAuthorizationManager() {
        return authorizationManager;
    }

    public DangerousCommandConfirmationManager getDangerousConfirm() {
        return dangerousConfirm;
    }

    public ClientSessionRegistry getClientSessionRegistry() {
        return clientSessionRegistry;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public JobManager getJobManager() {
        return jobManager;
    }

    public VmToolSessionRegistry getVmToolSessionRegistry() {
        return vmToolSessionRegistry;
    }

    public PerformanceOptimizer getPerformanceOptimizer() {
        return performanceOptimizer;
    }

    public SleuthSpyDispatcher getSpyDispatcher() {
        return spyDispatcher;
    }

    public EnhancementSessionRegistry getEnhancementSessionRegistry() {
        return enhancementSessionRegistry;
    }

    public static final class Builder {
        private final Instrumentation instrumentation;
        private final SleuthClassFileTransformer transformer;
        private Runnable shutdownHook;
        private ProductionConfig config;
        private AuditLogger auditLogger;
        private AuthenticationManager authenticationManager;
        private AuthorizationManager authorizationManager;
        private DangerousCommandConfirmationManager dangerousConfirm;
        private ClientSessionRegistry clientSessionRegistry;
        private MetricsCollector metricsCollector;
        private JobManager jobManager;
        private VmToolSessionRegistry vmToolSessionRegistry;
        private PerformanceOptimizer performanceOptimizer;
        private SleuthSpyDispatcher spyDispatcher;
        private EnhancementSessionRegistry enhancementSessionRegistry;

        private Builder(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
            this.instrumentation = instrumentation;
            this.transformer = transformer;
        }

        public Builder withShutdownHook(Runnable shutdownHook) {
            this.shutdownHook = shutdownHook;
            return this;
        }

        public Builder withConfig(ProductionConfig config) {
            this.config = config;
            return this;
        }

        public Builder withAuditLogger(AuditLogger auditLogger) {
            this.auditLogger = auditLogger;
            return this;
        }

        public Builder withAuthenticationManager(AuthenticationManager authenticationManager) {
            this.authenticationManager = authenticationManager;
            return this;
        }

        public Builder withAuthorizationManager(AuthorizationManager authorizationManager) {
            this.authorizationManager = authorizationManager;
            return this;
        }

        public Builder withDangerousConfirm(DangerousCommandConfirmationManager dangerousConfirm) {
            this.dangerousConfirm = dangerousConfirm;
            return this;
        }

        public Builder withClientSessionRegistry(ClientSessionRegistry clientSessionRegistry) {
            this.clientSessionRegistry = clientSessionRegistry;
            return this;
        }

        public Builder withMetricsCollector(MetricsCollector metricsCollector) {
            this.metricsCollector = metricsCollector;
            return this;
        }

        public Builder withJobManager(JobManager jobManager) {
            this.jobManager = jobManager;
            return this;
        }

        public Builder withVmToolSessionRegistry(VmToolSessionRegistry vmToolSessionRegistry) {
            this.vmToolSessionRegistry = vmToolSessionRegistry;
            return this;
        }

        public Builder withPerformanceOptimizer(PerformanceOptimizer performanceOptimizer) {
            this.performanceOptimizer = performanceOptimizer;
            return this;
        }

        public Builder withSpyDispatcher(SleuthSpyDispatcher spyDispatcher) {
            this.spyDispatcher = spyDispatcher;
            return this;
        }

        public Builder withEnhancementSessionRegistry(EnhancementSessionRegistry enhancementSessionRegistry) {
            this.enhancementSessionRegistry = enhancementSessionRegistry;
            return this;
        }

        public CommandProcessorFactoryRequest build() {
            return new CommandProcessorFactoryRequest(this);
        }
    }
}
