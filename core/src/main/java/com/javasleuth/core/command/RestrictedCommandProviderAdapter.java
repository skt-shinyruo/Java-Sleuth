package com.javasleuth.core.command;

import com.javasleuth.core.command.spi.RestrictedCommandProvider;
import java.util.Collection;

/**
 * Adapts the public restricted plugin SPI to the registry's internal provider contract.
 */
public final class RestrictedCommandProviderAdapter implements CommandProvider {
    private final RestrictedCommandProvider delegate;

    public RestrictedCommandProviderAdapter(RestrictedCommandProvider delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate is required");
        }
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public CommandProviderInfo getInfo() {
        CommandProviderInfo info = delegate.getInfo();
        if (info == null) {
            return CommandProviderInfo.plugin(
                delegate.getName(),
                delegate.getNamespace(),
                CommandProviderInfo.CURRENT_API_VERSION,
                java.util.Collections.singletonList("commands"),
                true
            );
        }
        if (info.isBuiltin()) {
            return CommandProviderInfo.plugin(
                info.getProviderName(),
                info.getProviderName(),
                info.getApiVersion(),
                info.getCapabilities(),
                info.isExposeUnqualifiedCommands()
            );
        }
        return info;
    }

    @Override
    public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
        return delegate.getCommandDescriptors(context != null ? context.toRestrictedContext() : null);
    }
}
