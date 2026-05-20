# Command Provider Metadata

Command providers must publish command behavior through `CommandMeta`. The metadata is the single source used by authorization, confirmation, caching, streaming, rate limiting, and precheck gates.

## External Plugin SPI

External jars should implement the restricted plugin API:

```java
package com.example.sleuth;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandDescriptor;
import com.javasleuth.core.command.spi.RestrictedCommandProvider;
import com.javasleuth.core.command.spi.RestrictedCommandProviderContext;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.Collection;
import java.util.Collections;

public final class ExampleProvider implements RestrictedCommandProvider {
    public String getName() {
        return "example";
    }

    public String getNamespace() {
        return "example";
    }

    public Collection<CommandDescriptor> getCommandDescriptors(RestrictedCommandProviderContext context) {
        return Collections.singletonList(
            CommandDescriptor.of("hello", new Command() {
                public String execute(String[] args) {
                    return "hello";
                }

                public String getDescription() {
                    return "Example plugin command";
                }
            }, CommandMeta.viewer(true, false))
        );
    }
}
```

Publish the provider with:

```text
META-INF/services/com.javasleuth.core.command.spi.RestrictedCommandProvider
```

The service file contains the provider class name, for example:

```text
com.example.sleuth.ExampleProvider
```

`RestrictedCommandProviderContext` only exposes stable support services such as read-only config and audit logging. It does not expose `Instrumentation`, class transformers, spy dispatchers, auth managers, job/session registries, or other core-owned internals.

Directory plugins require supply-chain opt-in:

```properties
plugins.enabled=true
plugins.directory=/opt/java-sleuth/plugins
plugins.allowlist.sha256=example-plugin.jar:<sha256hex>
```

`plugins.unsafe.allow-all-jars=true` restores the old "load every jar in the directory" behavior and should only be used in controlled development environments.

## Legacy Provider Bridge

The old `com.javasleuth.core.command.CommandProvider` service remains for core internals and deprecated plugin compatibility, but external jars are not loaded through it by default. To run an old plugin temporarily:

```properties
plugins.enabled=true
plugins.allowlist.sha256=old-plugin.jar:<sha256hex>
plugins.unsafe.legacy-provider-bridge.enabled=true
```

Even through the bridge, external providers receive a restricted compatibility context. Migrate old plugins to `RestrictedCommandProvider` instead of depending on core internals.

## Required Bootstrap Classes

Commands that install bytecode enhancers whose injected code calls bootstrap-side APIs must declare the required bootstrap-visible classes:

```java
CommandMeta.operator(false, true)
    .requiresBootstrap(BootstrapBridge.SPY_API);
```

`PrecheckPipeline` reads this metadata and denies non-help invocations when the bootstrap bridge is unavailable. Help-like invocations are still allowed so users can inspect command usage.

Use binary class names such as `com.example.BridgeApi`. The `foundation` module does not depend on core bootstrap constants; built-in commands pass `BootstrapBridge.SPY_API` from core.

## Capabilities

Capabilities describe operational behavior that policy, UI, audit, and docs can consume without hard-coded command-name checks:

```java
CommandMeta.operator(false, true)
    .requiresBootstrap(BootstrapBridge.SPY_API)
    .withCapability(CommandCapability.LONG_RUNNING);

CommandMeta.admin(false, false)
    .withCapability(CommandCapability.WRITES_DISK);
```

Available capabilities:

- `USES_INSTRUMENTATION`: command touches loaded classes, retransforms classes, or installs enhancers.
- `LONG_RUNNING`: command can run beyond a quick request-response interaction.
- `WRITES_DISK`: command writes files to disk.

`requiresBootstrap(...)` automatically adds `USES_INSTRUMENTATION`.

## Compatibility

Existing constructors and factories remain valid:

```java
CommandMeta.viewer(true, false);
CommandMeta.operator(false, true);
CommandMeta.admin(false, false);
```

Add capability metadata when the command needs the corresponding precheck or policy behavior. Do not duplicate command-name checks outside metadata.
