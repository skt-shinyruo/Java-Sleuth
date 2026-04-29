package com.javasleuth.core.command;

import com.javasleuth.core.command.plugin.CommandProviderLoader;

/**
 * Builds command registry and pipeline dependencies.
 */
final class CommandSubsystemFactory {
    private CommandSubsystemFactory() {}

    static CommandSubsystem create(RuntimeServices services) {
        if (services == null) {
            throw new IllegalArgumentException("services is required");
        }

        CommandProviderContext providerContext = new CommandProviderContext(
            services.instrumentation,
            services.transformer,
            services.metricsCollector,
            services.config,
            services.auditLogger,
            services.shutdownHook,
            services.authenticationManager,
            services.dangerousConfirm,
            services.jobManager,
            services.vmToolSessionRegistry,
            services.performanceOptimizer,
            services.spyDispatcher,
            services.enhancementSessionRegistry
        );

        BuiltinCommandProvider builtinProvider = new BuiltinCommandProvider();
        CommandProviderLoader providerLoader =
            new CommandProviderLoader(services.config, services.auditLogger, CommandProcessorFactory.class.getClassLoader());
        CommandProviderLoader.LoadedProviders loadedProviders = providerLoader.load(builtinProvider);

        CommandRegistry registry = new CommandRegistry(
            services.config,
            services.metricsCollector,
            services.auditLogger,
            loadedProviders.getProviders(),
            loadedProviders.getPluginClassLoader(),
            providerContext
        );

        CommandPipeline pipeline = new CommandPipeline(
            services.inputValidator,
            services.authorizationManager,
            services.dangerousConfirm,
            services.config,
            services.performanceOptimizer
        );

        return new CommandSubsystem(registry, pipeline);
    }
}
