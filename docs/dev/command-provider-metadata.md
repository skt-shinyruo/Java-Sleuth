# Command Provider Metadata

Command providers must publish command behavior through `CommandMeta`. The metadata is the single source used by authorization, confirmation, caching, streaming, rate limiting, and precheck gates.

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
