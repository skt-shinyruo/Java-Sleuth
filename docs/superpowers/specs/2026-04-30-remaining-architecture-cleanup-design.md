# Remaining Architecture Cleanup

## Summary

Java-Sleuth has already landed several architecture hardening changes:

- Enhancement sessions are tracked through `EnhancementSessionRegistry`.
- Command capabilities are partly metadata-driven through `CommandMeta` and `CommandCapability`.
- `CommandProcessorFactory` delegates most assembly work to focused factories.
- Short commands and foreground stream commands have separate executors.
- Runtime config writes are schema-validated.
- The bootstrap/container boundary has a contract version handshake.
- `CommandSpec` exists and is used by several instrumentation commands.

This spec completes the remaining cleanup. The goal is not a broad rewrite; it is a staged
convergence of partially completed architecture work:

- Split monolithic built-in command registration into domain providers.
- Expose a unified command surface for enhancement session listing and stopping.
- Change foreground stream execution from "wait for stream completion" to "wait for startup".
- Finish the `CommandSpec` migration for high-drift built-in commands.
- Close the last command-layer raw config access paths.
- Retire deprecated `CommandProcessorFactory` overload usage internally.

## Problem

Several systems now have the right primitives but still expose old structure or old behavior.

`BuiltinCommandProvider` still registers all built-in commands in one method. It mixes JVM
diagnostics, bytecode enhancement, runtime mutation, security operations, and basic operations.
This makes ownership, dependencies, command metadata, and future command placement harder to
review.

`EnhancementSessionRegistry` is active, and `reset`, `status`, detach, and command cleanup use it.
However, users still do not have one command surface to list or stop enhancement sessions. Each
enhancement command keeps private active-session state as an implementation detail, while `vmtool`
has its own `tracks/stop` surface.

`CommandExecutionEngine` has separate short and stream executors, but foreground streams still
call `task.get(timeout)`. A long `watch`, `trace`, `monitor`, `stack`, or `tt` no longer consumes
short-command worker threads, but the caller still waits for the stream lifecycle rather than just
stream startup.

`CommandSpec` exists, and the command pipeline parses spec-backed commands and renders generated
help. Some commands have migrated, but many still hand-parse positional arguments and options.
This keeps duplicated `Usage:` strings, inconsistent invalid-number behavior, and hidden parser
differences.

Runtime config writes are schema checked, but command-layer reads still have a few raw-key escape
paths. `ConfigCommand get` can request arbitrary keys, and `ConfigView` raw getters remain easy to
use from new command code.

`CommandProcessorFactory` is now a facade, but older positional overloads remain. They are
deprecated compatibility entry points, but internal code and tests should no longer normalize on
those overloads.

## Goals

- Keep built-in command registration behavior and command names stable.
- Move built-in command construction into small domain providers with explicit dependencies.
- Preserve plugin provider APIs and `CommandRegistry` behavior.
- Add one unified enhancement session command for listing and stopping sessions across
  `watch`, `trace`, `monitor`, `stack`, `tt`, and `vmtool`.
- Treat command-specific `activeSessions` maps as private resource tables, not public session
  registries.
- Let foreground stream commands return from the execution engine after startup succeeds.
- Keep cancellation unified through `CancellationTokenSource`, client disconnect cleanup, reset,
  detach, and job stop.
- Migrate high-drift commands to `CommandSpec` in small batches.
- Ensure generated help and parser validation use the same command declarations.
- Restrict command-layer config reads to schema keys or typed snapshots.
- Move internal factory usage to `CommandProcessorFactoryRequest`.

## Non-Goals

- Do not change command names or remove existing command syntax in this cleanup.
- Do not remove plugin support or legacy map-based providers.
- Do not remove existing command-specific stop surfaces such as `vmtool stop` in the first pass.
- Do not rewrite all command implementations at once.
- Do not remove deprecated public factory overloads in the first pass.
- Do not introduce an external CLI parser or dependency injection framework.
- Do not make stream executors unbounded.
- Do not change the wire protocol.

## Approach Considered

### Recommended: Staged Convergence

Complete each partially migrated area with small compatibility-preserving steps.

Advantages:

- Keeps behavior stable while improving boundaries.
- Allows focused tests per phase.
- Uses existing primitives already present in the codebase.
- Avoids a risky command runtime rewrite.

Trade-off:

- For a short period, both old and new command parser styles coexist.

### Alternative: Big-Bang Command Runtime Rewrite

Replace built-in providers, parser flow, stream execution, and config access in one change.

Advantages:

- Reaches the final shape faster on paper.

Trade-off:

- Too much blast radius across command execution, auth/precheck, streaming, plugins, jobs, and
  tests.

### Alternative: Leave Compatibility Structure As-Is

Keep the current primitives and only fix bugs as they appear.

Advantages:

- No immediate churn.

Trade-off:

- Future commands will continue copying old parser/config/provider patterns.
- Review risk remains high because ownership and metadata are scattered.

## Target Design

### Phase 1: Domain Providers for Built-In Commands

Keep `BuiltinCommandProvider` as the single provider passed to `CommandProviderLoader`, but make it
a small aggregator. Add package-private domain provider classes under
`core/src/main/java/com/javasleuth/core/command/`.

Initial classes:

- `JvmDiagnosticsCommandProvider`
- `EnhancementCommandProvider`
- `RuntimeMutationCommandProvider`
- `SecurityOpsCommandProvider`
- `OperationsCommandProvider`

Each class exposes:

```java
final class EnhancementCommandProvider {
    Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context);
}
```

The public `BuiltinCommandProvider` keeps:

```java
public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
    List<CommandDescriptor> descriptors = new ArrayList<>();
    descriptors.addAll(new JvmDiagnosticsCommandProvider().getCommandDescriptors(context));
    descriptors.addAll(new EnhancementCommandProvider().getCommandDescriptors(context));
    descriptors.addAll(new RuntimeMutationCommandProvider().getCommandDescriptors(context));
    descriptors.addAll(new SecurityOpsCommandProvider().getCommandDescriptors(context));
    descriptors.addAll(new OperationsCommandProvider().getCommandDescriptors(context));
    return descriptors;
}
```

Suggested command ownership:

- `JvmDiagnosticsCommandProvider`
  - `dashboard`, `thread`, `jvm`, `memory`, `sc`, `sm`, `classloader`, `logger`, `mbean`,
    `getstatic`
- `EnhancementCommandProvider`
  - `watch`, `trace`, `monitor`, `stack`, `tt`, `vmtool`, `jobs`, `reset`, `status`
- `RuntimeMutationCommandProvider`
  - `redefine`, `retransform`, `mc`, `jad`, `dump`, `heapdump`, `sysprop`, `sysenv`,
    `vmoption`
- `SecurityOpsCommandProvider`
  - `auth`, `session`, `perm`, `audit`
- `OperationsCommandProvider`
  - `health`, `metrics`, `config`, `version`, `quit`, `stop`

Metadata helper methods such as `instrumentationStreamMeta()` and `writesDisk(...)` move to a
package-private utility:

```java
final class BuiltinCommandMetas {
    static CommandMeta instrumentationStream();
    static CommandMeta writesDisk(CommandMeta meta);
}
```

Behavior rules:

- Command registration order should remain stable unless a test documents and approves a new
  order.
- `CommandProviderInfo` for `BuiltinCommandProvider` remains unchanged.
- No plugin API changes are introduced.

Tests:

- Add or update a built-in provider test that asserts the full command set before and after the
  split is unchanged.
- Add focused tests that each domain provider registers expected representative commands.
- Keep capability metadata tests for instrumentation and disk-writing commands.

### Phase 2: Unified Enhancement Session Command Surface

Add a new built-in command, registered by `EnhancementCommandProvider`:

```text
enhance sessions [--kind <watch|trace|monitor|stack|tt|vmtool|other>]
enhance stop <session-id>
enhance stop --kind <watch|trace|monitor|stack|tt|vmtool|other>
enhance stop --client <client-id>
```

Command implementation:

```java
public final class EnhanceCommand implements Command, SpecBackedCommand {
    private final EnhancementSessionRegistry registry;
}
```

`CommandSpec` declares subcommands and options. The command should use generated help.

Behavior:

- `enhance sessions` renders active sessions from `EnhancementSessionRegistry.list()`.
- `--kind` filters by `EnhancementSessionKind`.
- `enhance stop <session-id>` calls `registry.close(sessionId, "enhance_stop")`.
- `enhance stop --client <client-id>` calls `registry.closeByClient(clientId, "enhance_stop")`.
- `enhance stop --kind <kind>` closes all currently listed sessions matching that kind.
- If no sessions match, return a clear "No matching enhancement sessions" message.
- If a close operation fails, include failed session ids and failure messages from registry
  summaries when available.

Compatibility:

- Existing command-specific cleanup remains unchanged.
- Existing `vmtool stop` remains available.
- Command-specific `activeSessions` maps remain as private closer state.
- `reset`, detach, and resource close still call `registry.closeAll(...)`.

Recommended `EnhancementSessionRegistry` additions:

```java
EnhancementSessionCloseSummary closeByKind(EnhancementSessionKind kind, String reason);
EnhancementSessionCloseSummary closeMatching(Predicate<EnhancementSessionSnapshot> predicate, String reason);
```

If Java 8 predicate dependencies are considered too broad for this class, implement only
`closeByKind(...)` and keep client/session-id paths explicit.

Tests:

- Registry test for `closeByKind`.
- Command test for listing sessions by kind.
- Command test for stopping by id.
- Command test for stopping by client id.
- Reset/detach tests should continue to pass without command-specific knowledge.

### Phase 3: Foreground Stream Startup Handles

Change `CommandExecutionEngine.executeStreamWithTimeout(...)` so it waits for stream startup, not
stream completion.

Add an internal startup latch around stream execution:

```java
final class StreamStartup {
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<Throwable> startupFailure = new AtomicReference<>();

    void started();
    void failed(Throwable t);
    void await(long timeoutMs);
}
```

Execution flow:

1. Create `CancellationTokenSource`.
2. Submit stream task to `streamCommandExecutor`.
3. Stream task applies `context.withCancellationToken(source.token())`.
4. Stream task calls `startup.started()` immediately before invoking `command.executeStream(...)`.
5. Caller waits for startup using a short startup timeout.
6. After startup succeeds, `executeStream(...)` returns to the caller while the stream task
   continues writing to `StreamSink`.

Timeouts:

- Keep `performance.command.timeout` as a command lifecycle timeout for sync commands.
- Add stream startup config:
  - `performance.command.stream.startup.timeout.ms`, default `3000`, range `100..60000`
- Long-running stream duration should remain controlled by command options such as `--timeout`
  and by cancellation.

Cancellation:

- On startup timeout, cancel token and `Future.cancel(true)`.
- On queue rejection, release impact permit and fail immediately.
- On client disconnect surfaced through `StreamSink`, stream task cancels token and exits.
- On `CommandExecutionEngine.shutdown()`, executor shutdown interrupts running streams.

Impact permits:

- Today high-impact permits are released when the stream task finishes.
- Preserve that behavior for high-impact stream commands so the permit covers the active stream
  lifecycle.
- If a stream task is removed from the queue before running, release the permit in the caller as
  today.

Error reporting:

- Startup failures should be propagated synchronously to the command caller.
- Runtime stream failures after startup should be sent to `sink.error(...)` when possible and then
  close the sink.

Tests:

- A long stream command starts and returns from pipeline execution without waiting for completion.
- A long stream command does not block a sync command on the short executor.
- Startup timeout cancels the stream token and future.
- Queue rejection message still distinguishes the stream pool.
- High-impact stream permit remains held until the stream task exits.

### Phase 4: CommandSpec Migration

Adopt a "new or touched commands must be spec-backed" rule for built-ins. Migrate in batches.

Batch 1: operational commands with simple syntax:

- `jobs`
- `config`
- `audit`
- `logger`
- `vmoption`

Batch 2: diagnostics and file-producing commands:

- `sc`
- `sm`
- `classloader`
- `mbean`
- `heapdump`
- `dump`
- `jad`

Batch 3: enhancement command internals:

- complete `tt` record/detail/replay/stop spec coverage
- complete stack parser replacement
- remove remaining hand-written `vmtool` usage branches where subcommand specs can express the
  same validation

Enhance `CommandSpec` before migrating batch 2 if needed:

- Render subcommand-specific help for invocations such as `vmtool track --help`.
- Add string allowed-values validation for options such as log level.
- Add parser support for reusable custom validators where simple range checks are not enough.
- Keep Java 8 compatibility.

Migration rules:

- `CommandDescriptor.ofSpec(...)` should be preferred for migrated built-ins.
- A command with a `CommandSpec` should not manually render top-level usage.
- Manual validation may remain only for domain validation that cannot be expressed in `CommandSpec`
  yet, such as JMX `ObjectName` parsing or method invocation target checks.
- `CommandArgs` remains for compatibility but should not gain new users.

Tests:

- Expand `BuiltinCommandSpecTest` to cover each migrated command.
- Add help rendering snapshots or structured assertions for representative subcommands.
- Add parse failure tests for invalid numbers, duplicate options, missing option values, and
  unknown options.
- Add a static-style test that migrated commands are registered through `CommandDescriptor.ofSpec`.

### Phase 5: Config Schema Read Boundary

Keep `ConfigView` raw getters for low-level compatibility, but make command-layer usage explicit.

Add convenience read APIs:

```java
public <T> T read(ConfigKey<T> key);
public SleuthConfig typedSnapshot();
public String getKnownRaw(ConfigKey<?> key);
```

These can live on `ProductionConfig` first. If broadening is useful later, add default methods to
`ConfigView`.

Command rules:

- Built-in commands should use `SleuthConfigParser.parse(config.snapshot())`, `typedSnapshot()`, or
  `ConfigKey.read(config)`.
- New command code should not call `config.getString("literal.key", ...)`.
- `ConfigCommand get` should only accept keys from `SleuthConfigSchema.byKey(key)`.
- Unknown keys return `Unknown config key: <key>`.
- Sensitive keys continue to use `SensitiveKeyMasker`.

Allowed exceptions:

- `SleuthLogger` provider integration can remain raw because it is a low-level logging boundary.
- Config loading, snapshotting, schema validation, and tests may use raw getters.

Tests:

- `config get unknown.key` returns unknown-key error.
- `config get security.auth.admin.password` masks the value.
- Runtime set validation tests continue to pass.
- A source scan test can fail on new raw command-layer `config.getString("...")` usage outside
  explicit allowlisted files.

### Phase 6: Factory Compatibility Cleanup

Keep deprecated `CommandProcessorFactory` positional overloads in the first implementation, but
stop using them internally.

Rules:

- Production code should use `CommandProcessorFactoryRequest.builder(...)`.
- Tests should use the builder unless they specifically test deprecated compatibility overloads.
- Add Javadoc to deprecated overloads pointing to `CommandProcessorFactoryRequest`.
- Keep one compatibility test per overload family rather than many tests using deprecated paths.

Later removal criteria:

- No internal production callers use deprecated overloads.
- Tests only reference them in compatibility tests.
- A release note or migration doc points users to `CommandProcessorFactoryRequest`.

## Data Flow

Built-in command registration:

```text
CommandSubsystemFactory
  -> BuiltinCommandProvider
    -> JvmDiagnosticsCommandProvider
    -> EnhancementCommandProvider
    -> RuntimeMutationCommandProvider
    -> SecurityOpsCommandProvider
    -> OperationsCommandProvider
  -> CommandRegistry
```

Enhancement session stop:

```text
enhance stop
  -> CommandPipeline precheck/authz
  -> EnhanceCommand
  -> EnhancementSessionRegistry.close/closeByKind/closeByClient
  -> command-specific closer
  -> transformer/dispatcher/vmtool cleanup
```

Foreground stream:

```text
CommandPipeline.executeStreamPrechecked
  -> CommandExecutionEngine.executeStream
  -> stream executor task starts
  -> startup latch opens
  -> caller returns
  -> stream task writes to StreamSink until timeout/cancel/completion
```

Config reads:

```text
command
  -> typed snapshot or ConfigKey
  -> SleuthConfigSchema / SleuthConfigParser
  -> ProductionConfig snapshot/runtime overrides
```

## Error Handling

- Provider split must fail fast if a domain provider returns duplicate command names; existing
  `CommandRegistry` duplicate handling remains authoritative.
- `enhance stop` should be best-effort for multi-session operations and report counts plus
  failures.
- Stream startup timeout should return a clear startup timeout error, not a generic command
  timeout.
- Stream runtime exceptions after startup should be sent to the sink and logged with an error id
  if the existing pipeline context provides one.
- Config unknown-key errors should not reveal sensitive values.
- Deprecated factory overloads should behave exactly as before.

## Implementation Plan

This spec should be implemented as separate commits or PR-sized phases:

1. Split built-in providers and add provider tests.
2. Add `enhance` session command and registry close-by-kind support.
3. Change stream execution to startup-wait semantics with tests.
4. Migrate CommandSpec batch 1.
5. Migrate CommandSpec batch 2.
6. Migrate CommandSpec batch 3.
7. Add config read boundary APIs and tighten `config get`.
8. Move internal factory/test callers to `CommandProcessorFactoryRequest`.

Each phase should compile and pass focused tests before the next phase starts.

## Verification

Minimum verification after each phase:

```bash
mvn -pl core -DskipTests compile
mvn -pl foundation -DskipTests compile
mvn test
```

Focused tests to add or update:

- `BuiltinCommandProviderSplitTest`
- `EnhanceCommandTest`
- `EnhancementSessionRegistryTest`
- `CommandExecutionEngineStreamStartupTest`
- `BuiltinCommandSpecTest`
- `ConfigCommandSchemaBoundaryTest`
- `CommandProcessorFactoryRequestUsageTest`

Existing tests that must continue passing:

- `BuiltinCommandCapabilityTest`
- `PrecheckPipelineBootstrapCapabilityTest`
- `EnhancementSessionResetTest`
- `CommandExecutionEngineIsolationTest`
- `RuntimeServicesFactoryTest`
- `CommandSubsystemFactoryTest`
- `ServerSubsystemFactoryTest`

## Acceptance Criteria

- `BuiltinCommandProvider` is a small aggregator and no longer constructs every built-in command
  directly.
- Built-in command names and metadata remain stable unless explicitly changed by tests.
- Users can list and stop active enhancement sessions through one `enhance` command.
- `reset`, detach, and shutdown still close all enhancement sessions.
- A long foreground stream does not keep the caller waiting for the entire stream duration after
  startup succeeds.
- Stream cancellation still works for timeout, disconnect, job stop, reset, and detach paths.
- Migrated commands use generated help and parser validation from `CommandSpec`.
- New command-layer config reads use typed snapshots or schema keys.
- Deprecated factory overloads remain available but are no longer the normal internal path.
