# Command Capability Metadata

## Summary

Java-Sleuth command prechecks should be driven by command metadata instead of command-name switch statements.

`PrecheckPipeline.BootstrapBridgeStep` currently hard-codes which commands require bootstrap bridge visibility:

- `watch`
- `trace`
- `monitor`
- `tt`
- `stack`
- `vmtool`

Those commands all enable bytecode enhancement paths that inject calls to bootstrap-side classes through `SleuthSpyAPI`. The current runtime still has a second safety gate in `SleuthClassFileTransformer.addEnhancer(...)`, but the precheck layer is not using the same metadata model as authorization, rate limiting, audit, caching, streaming, and dangerous confirmation.

This spec moves command capability rules into `CommandMeta`. The immediate implementation adds capability metadata for bootstrap requirements and general operational traits such as instrumentation, long-running execution, and disk writes. `PrecheckPipeline` reads those capabilities from `CommandMeta`; it no longer decides by command name.

## Problem

The current command pipeline mixes two sources of truth:

- `CommandMeta` drives role checks, subcommand role checks, audit, rate limiting, streamability, caching, dangerous confirmation, and impact confirmation.
- `PrecheckPipeline.BootstrapBridgeStep` separately hard-codes command names that require `BootstrapBridge.SPY_API`.

That split creates maintenance risk:

- Adding a new instrumentation command requires updating command registration and remembering to update `PrecheckPipeline`.
- Plugin commands can declare `CommandMeta`, but cannot declare bootstrap bridge requirements in the same metadata path.
- Precheck failures become inconsistent: authorization/confirmation failures are metadata-driven, but bridge failures are command-name-driven.
- Higher-level policy decisions such as `longRunning`, `writesDisk`, or `usesInstrumentation` have no canonical metadata home.

The runtime transformer check should remain, but it is too late to be the only visible policy gate. Users should see predictable precheck denial before command execution attempts to install enhancers.

## Goals

- Make command metadata the single source of truth for precheck-relevant command capabilities.
- Remove the command-name switch from `PrecheckPipeline.BootstrapBridgeStep`.
- Preserve existing behavior for `watch`, `trace`, `monitor`, `tt`, `stack`, and `vmtool`.
- Keep `CommandMeta` backward-compatible for existing callers and plugins.
- Keep `foundation` independent from `core` by storing bootstrap class names as strings.
- Keep `SleuthClassFileTransformer` bootstrap checks as a runtime safety net.
- Add tests that fail when a bootstrap-dependent command is registered without the required metadata.

## Non-Goals

- Do not redesign the command parser.
- Do not replace `BootstrapDependentEnhancer`.
- Do not remove transformer-level bootstrap checks.
- Do not introduce fine-grained policy enforcement for every new capability in this change.
- Do not break existing plugin implementations that use `new CommandMeta(...)`, `CommandMeta.viewer(...)`, `operator(...)`, or `admin(...)`.
- Do not implement subcommand-level bootstrap predicates in the first pass.

## Approach Considered

### Recommended: Extend `CommandMeta`

Add capability fields and fluent methods to `CommandMeta`.

Advantages:

- Fits the existing architecture: security and precheck already receive `CommandMeta`.
- Keeps `CommandDescriptor` as the command registration single source of truth.
- Works for built-in and plugin commands.
- Avoids adding another registry or mapping layer.

Trade-off:

- `CommandMeta` grows beyond strictly security metadata. This is acceptable because it already contains execution traits such as cacheability and streamability.

### Alternative: Add `CommandCapability` to `CommandDescriptor`

Keep `CommandMeta` focused on security and add a separate capability object beside it.

Advantages:

- Cleaner conceptual separation between security metadata and operational capabilities.

Trade-off:

- Requires changing `CommandDescriptor`, `CommandRegistry.Entry`, provider compatibility paths, and pipeline invocation objects.
- Recreates a parallel metadata channel when `CommandMeta` already flows through every relevant precheck step.

### Alternative: Marker Interfaces on Commands

Add interfaces such as `RequiresBootstrapBridge`, `UsesInstrumentation`, or `WritesDisk`.

Advantages:

- Capability is close to command implementation.

Trade-off:

- Does not help legacy map-based metadata paths.
- Makes policy harder to inspect without instantiating commands.
- Does not express subcommand role-like rules as cleanly as metadata.

## Target Design

### `CommandCapability`

Create a new enum in `foundation`:

```java
package com.javasleuth.foundation.security;

public enum CommandCapability {
    USES_INSTRUMENTATION,
    LONG_RUNNING,
    WRITES_DISK
}
```

These capabilities are descriptive in the first pass. They establish a metadata vocabulary for future policy gates, UI warnings, command listing, documentation generation, and auditing.

### Bootstrap Requirement Metadata

Extend `CommandMeta` with:

```java
private final Set<CommandCapability> capabilities;
private final Set<String> requiredBootstrapClasses;
```

Add read APIs:

```java
public boolean hasCapability(CommandCapability capability);
public Set<CommandCapability> getCapabilities();
public boolean requiresBootstrap();
public Set<String> getRequiredBootstrapClasses();
```

Add fluent APIs:

```java
public CommandMeta withCapability(CommandCapability capability);
public CommandMeta withCapabilities(Collection<CommandCapability> capabilities);
public CommandMeta requiresBootstrap(String binaryClassName);
public CommandMeta requiresBootstrap(Collection<String> binaryClassNames);
```

Rules:

- Null or blank bootstrap class names are ignored.
- Returned collections are immutable.
- Existing constructors delegate to defaults: empty capabilities and empty bootstrap requirements.
- Existing `withAudit`, `withRateLimit`, `withDangerous`, `withImpact`, and `withSubcommandRole` preserve capabilities and bootstrap requirements.
- `requiresBootstrap(...)` implies `CommandCapability.USES_INSTRUMENTATION`.

`foundation` must not import `BootstrapBridge`; built-in commands pass `BootstrapBridge.SPY_API` from `core`.

### Built-In Command Metadata

Update built-in command registration:

```java
private static CommandMeta instrumentationStreamMeta() {
    return CommandMeta.operator(false, true)
        .requiresBootstrap(BootstrapBridge.SPY_API)
        .withCapability(CommandCapability.LONG_RUNNING);
}
```

Use this for:

- `watch`
- `trace`
- `monitor`
- `tt`
- `stack`

Update `vmtool`:

```java
CommandMeta.operator(false, false)
    .requiresBootstrap(BootstrapBridge.SPY_API)
    .withCapability(CommandCapability.LONG_RUNNING)
    .withImpact(CommandMeta.ImpactLevel.MEDIUM)
    .withRateLimit(10)
    .withSubcommandRole("invoke", UserRole.ADMIN)
    .withSubcommandRole("invoke-static", UserRole.ADMIN)
    .withSubcommandRole("invokestatic", UserRole.ADMIN)
```

This preserves the current conservative command-level behavior: all `vmtool` invocations require the bridge precheck even though only `track` installs an instance tracker. Fine-grained subcommand capability can be added later.

Disk-writing commands can also be annotated in the same pass where the command can write files:

- `heapdump`
- `dump`
- `mc`

These annotations do not enforce new behavior yet unless a dedicated policy gate is added later.

### Precheck Pipeline

Change `BootstrapBridgeStep` to read metadata:

```java
Set<String> required = state.meta != null
    ? state.meta.getRequiredBootstrapClasses()
    : Collections.emptySet();
```

For each required bootstrap class:

1. Keep the existing help-like bypass.
2. Call `BootstrapBridge.canEnableEnhancement(requiredClass, null)`.
3. Return a denied decision using `BootstrapBridge.formatDisabledMessage(cmd, requiredClass)` on the first failure.

Remove `requiredBootstrapClassForCommand(String cmd)`.

The step remains before authorization. This preserves the existing order and still lets help-like invocations through even when bootstrap classes are unavailable.

### Runtime Safety Gate

Keep `SleuthClassFileTransformer.addEnhancer(...)` unchanged. It continues to inspect `BootstrapDependentEnhancer` and reject unsafe enhancement at the point of registration.

The two checks serve different purposes:

- Metadata precheck gives early, command-level user feedback and consistent policy behavior.
- Transformer check protects the target JVM if a command or plugin incorrectly declares metadata.

### Plugin Compatibility

No existing plugin source should break:

- Existing `CommandMeta` constructors remain available.
- Static factories remain available.
- New capability APIs are optional.
- Plugins that need bootstrap-visible classes can opt in with `requiresBootstrap("...")`.

Plugin authors should be documented to declare capabilities whenever command execution may:

- install a `BootstrapDependentEnhancer`
- retransform loaded classes
- run beyond the request-response lifetime
- write files to disk

### Subcommand Granularity

The first pass is command-level only.

This matches the current behavior and avoids pulling command-specific argument parsing into `CommandMeta`. Commands such as `tt` and `vmtool` have read-only subcommands, but making only those bypass bootstrap checks requires a more expressive API:

```java
public CommandMeta requiresBootstrapWhen(CommandCapabilityPredicate predicate, String className);
```

That is intentionally deferred. The implementation should not create an ad hoc predicate just for `tt` or `vmtool`.

## Test Plan

### Unit Tests

Add `CommandMetaTest` coverage:

- Default meta has no capabilities and no bootstrap requirements.
- `requiresBootstrap("x.y.Z")` records the class and implies `USES_INSTRUMENTATION`.
- Existing `with...` methods preserve capabilities and bootstrap requirements.
- Null and blank bootstrap class names are ignored.
- Returned collections are immutable.

Add `PrecheckPipeline` coverage:

- A command with `requiresBootstrap("missing.TestBridge")` is denied when not help-like.
- A help-like invocation with the same metadata is allowed through the bridge step.
- A command without bootstrap requirements does not trigger bridge denial.
- The denial message includes the command name and required bootstrap class.

Add built-in registration coverage:

- `watch`, `trace`, `monitor`, `tt`, `stack`, and `vmtool` entries all require `BootstrapBridge.SPY_API`.
- At least one non-instrumentation command, such as `thread` or `jvm`, does not require bootstrap.

### Integration/Regression Tests

Existing command execution and stream tests should continue to pass.

Run at minimum:

```bash
mvn -pl core test
```

If foundation has separate tests, also run:

```bash
mvn -pl foundation test
```

## Migration Steps

1. Add `CommandCapability`.
2. Extend `CommandMeta` fields, constructors, getters, and fluent methods.
3. Update all existing `CommandMeta` copy methods to preserve new fields.
4. Update `BuiltinCommandProvider` metadata for instrumentation commands.
5. Replace `PrecheckPipeline.requiredBootstrapClassForCommand(...)` with metadata lookup.
6. Add tests for metadata behavior and precheck behavior.
7. Add lightweight docs for plugin authors in command/provider documentation.

## Acceptance Criteria

- No command-name switch remains in `PrecheckPipeline` for bootstrap bridge requirements.
- Built-in enhancement commands declare `BootstrapBridge.SPY_API` through metadata.
- A plugin command can declare a bootstrap requirement through `CommandMeta`.
- Existing commands without bootstrap requirements keep their current behavior.
- Existing public `CommandMeta` constructors and factories remain source-compatible.
- Transformer-level bootstrap checks remain in place.
- Tests cover both metadata preservation and precheck behavior.

## Deferred Decisions

- Whether `WRITES_DISK` should immediately trigger dangerous confirmation, impact confirmation, or audit-only behavior.
- Whether `LONG_RUNNING` should influence default timeout, streaming preference, or command listing.
- Whether a later release should add subcommand-level capability predicates for `tt`, `vmtool`, and similar mixed-mode commands.
