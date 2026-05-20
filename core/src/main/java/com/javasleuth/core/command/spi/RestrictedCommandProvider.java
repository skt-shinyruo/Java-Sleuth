package com.javasleuth.core.command.spi;

import com.javasleuth.core.command.CommandDescriptor;
import com.javasleuth.core.command.CommandProviderInfo;
import java.util.Collection;
import java.util.Collections;

/**
 * Public extension API for external Java-Sleuth command plugins.
 *
 * <p>External plugins should implement this interface and publish it via
 * {@code META-INF/services/com.javasleuth.core.command.spi.RestrictedCommandProvider}. Plugins can
 * register commands and metadata, but do not receive core internals.</p>
 */
public interface RestrictedCommandProvider {
    String getName();

    default String getNamespace() {
        return getName();
    }

    default CommandProviderInfo getInfo() {
        return CommandProviderInfo.plugin(
            getName(),
            getNamespace(),
            CommandProviderInfo.CURRENT_API_VERSION,
            Collections.singletonList("commands"),
            true
        );
    }

    Collection<CommandDescriptor> getCommandDescriptors(RestrictedCommandProviderContext context);
}
