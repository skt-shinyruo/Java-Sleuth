# Runtime Command Hardening and CommandSpec Design

## Summary

This spec turns four runtime review items into one staged design:

- Split short command execution from long foreground stream execution.
- Add an explicit cancellation token shared by foreground streams and background jobs.
- Make runtime configuration updates and runtime reads go through `SleuthConfigSchema` / typed snapshots.
- Add a version handshake for the bootstrap/container cross-ClassLoader contract.
- Introduce a lightweight `CommandSpec` model for parsing, validation, help, defaults, ranges, and danger metadata.

The work is one architectural hardening effort, but each phase is independently testable and can be merged in order.

## Problem

The current implementation has four sources of operational drift.

`CommandExecutionEngine` uses one executor for both short synchronous commands and long foreground streams. A few `watch`, `trace`, or `monitor` streams can occupy all command workers and delay ordinary commands such as `status`, `health`, or `jobs stop`.

Runtime configuration has a schema, but command implementations still read raw string keys through `ProductionConfig.getInt(...)`, `getBoolean(...)`, and `getLong(...)`. `config set` accepts most values as long as the key is not forbidden, so invalid queue sizes or booleans are accepted and fail later or silently change semantics.

The thin agent intentionally uses strings and reflection across the bootstrap/container boundary, but the runtime only checks that several classes are visible. Mixed-version bootstrap/container deployments can pass class visibility checks and then fail with misleading messages such as “already attached” or “container jar not found.”

Command argument parsing and help are still handwritten in many commands. `watch`, `trace`, `monitor`, and `vmtool` repeat the same option patterns but have different invalid-number behavior and separate help strings.

## Goals

- Keep short commands responsive when long stream commands are running.
- Keep foreground stream cancellation, stream timeout, client disconnect cleanup, and background `jobs stop` on one cancellation model.
- Keep all runtime `config set` values type-checked and range-checked before storing them.
- Replace raw built-in command configuration reads with schema keys or a typed invocation snapshot.
- Make bootstrap bridge incompatibility fail early with explicit diagnostics.
- Centralize command option declarations for migrated commands.
- Generate migrated command help from the same declarations used for parsing and validation.
- Preserve the existing public `Command` and `StreamCommand` interfaces for plugins during the first implementation.
- Keep implementation Java 8 compatible.

## Non-Goals

- Do not remove `JobManager`; background jobs keep their existing bounded executor.
- Do not make stream executors unbounded.
- Do not change the wire protocol or client command syntax except for stricter invalid argument errors.
- Do not introduce a dependency injection framework or a third-party CLI parser.
- Do not implement plugin-defined configuration key registration in the first pass.
- Do not rewrite every command to `CommandSpec` in the first pass.
- Do not remove existing config keys such as `performance.command.executor.core`.

## Approach Considered

### Recommended: Staged Internal Hardening

Implement the four changes as internal framework improvements with compatibility adapters.

Advantages:

- Keeps existing command and plugin APIs working.
- Lets each phase land with focused tests.
- Avoids a large public API migration.
- Uses existing architecture: `CommandMeta`, `SleuthConfigSchema`, `CommandDescriptor`, `PrecheckPipeline`, and `JobManager`.

Trade-off:

- Some code paths briefly support both old and new styles while commands migrate to `CommandSpec`.

### Alternative: Big-Bang Command Runtime Rewrite

Replace `Command`, `StreamCommand`, config reads, and command registration in one change.

Advantages:

- Cleaner final shape with fewer compatibility adapters.

Trade-off:

- High regression risk across command execution, plugins, auth, stream handling, and job handling.
- Harder to review and verify.

### Alternative: Tactical Fixes Only

Split executors and add a bootstrap version check, but leave raw config and handwritten parsers alone.

Advantages:

- Smaller first change.

Trade-off:

- Leaves two known drift sources in place.
- Future command work continues copying parser/help/config patterns.

## Target Design

### Phase 1: Execution Pool Isolation and Cancellation

Modify `core/src/main/java/com/javasleuth/core/command/pipeline/CommandExecutionEngine.java`.

Replace the single executor:

```java
private final ThreadPoolExecutor commandExecutor;
```

with two bounded executors:

```java
private final ThreadPoolExecutor shortCommandExecutor;
private final ThreadPoolExecutor streamCommandExecutor;
```

Rules:

- `executeSync(...)` submits only to `shortCommandExecutor`.
- `executeStream(...)` submits only to `streamCommandExecutor`.
- Existing config keys `performance.command.executor.core`, `performance.command.executor.max`, and `performance.command.executor.queue.capacity` continue to size the short command pool.
- Add stream-specific keys in `foundation/src/main/java/com/javasleuth/foundation/config/schema/SleuthConfigSchema.java`:
  - `performance.command.stream.executor.core`, default `2`, range `1..64`
  - `performance.command.stream.executor.max`, default `4`, range `1..64`
  - `performance.command.stream.executor.queue.capacity`, default `32`, range `1..10000`
- Add matching fields and getters to `foundation/src/main/java/com/javasleuth/foundation/config/model/PerformanceConfig.java`.
- Parse the new values in `SleuthConfigParser.parsePerformance(...)`.
- If stream max is lower than stream core, normalize max to core and warn, matching existing command executor behavior.
- `shutdown()` closes both executors with `SleuthExecutors.shutdownAndAwait(...)`.
- Queue-full errors should distinguish pools:
  - short pool: `Server is busy: short command execution queue is full`
  - stream pool: `Server is busy: stream command execution queue is full`

Add cancellation primitives in `core/src/main/java/com/javasleuth/core/command/`:

```java
public interface CancellationToken {
    boolean isCancelled();
    void throwIfCancelled() throws InterruptedException;
}
```

```java
public final class CancellationTokenSource {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public CancellationToken token();
    public boolean cancel();
    public boolean isCancelled();
}
```

`CancellationTokenSource.token()` returns a stable token view. `throwIfCancelled()` throws `InterruptedException` after restoring no interrupt state; callers that catch it should call `Thread.currentThread().interrupt()` when appropriate.

Modify `core/src/main/java/com/javasleuth/core/command/CommandContext.java`:

- Add an optional `CancellationToken cancellationToken` field.
- Keep existing constructors and default the field to a non-cancelled token.
- Add `getCancellationToken()`.
- Add `withCancellationToken(CancellationToken token)` to return a copied context with the same client/session fields and the provided token.

Foreground streams:

- `CommandExecutionEngine.executeStreamWithTimeout(...)` creates a `CancellationTokenSource` before submitting the stream task.
- The stream task receives `context.withCancellationToken(source.token())` through `CommandContextHolder`.
- On timeout, interruption, or client disconnect, the engine calls `source.cancel()` before `task.cancel(true)`.
- The engine still interrupts with `Future.cancel(true)` because existing blocking calls rely on interrupt.

Background jobs:

- Modify `core/src/main/java/com/javasleuth/core/command/JobManager.java`.
- Replace or wrap `Job.cancelled` with `CancellationTokenSource cancelSource`.
- `JobManager.stop(...)` calls `cancelSource.cancel()`, `future.cancel(true)`, and interrupts the job thread.
- When running a job, `JobManager` sets `CommandContextHolder` to `capturedContext.withCancellationToken(j.cancelSource.token())`.
- `Job.cancelled.get()` checks become `j.cancelSource.isCancelled()`.

Long-running command loops must check the token near blocking waits:

- `WatchCommand` collection loop around result queue polling.
- `TraceCommand` collection loop around result queue polling.
- `MonitorCommand` sleep loop.
- `TtRecordEngine` polling loop.
- `StackTraceLiteEngine` polling loop.

The loop pattern is:

```java
CancellationToken token = currentCancellationToken();
while (!token.isCancelled() && eventCount < maxCount) {
    WatchResult r = resultQueue.poll(Math.min(remainingTime, 1000L), TimeUnit.MILLISECONDS);
    token.throwIfCancelled();
    // existing event handling
}
```

`currentCancellationToken()` is a small local helper in each migrated command or a shared helper in `CommandContextHolder`.

### Phase 2: Schema-Validated Runtime Config and Typed Reads

Modify `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigKey.java`.

Add a validation API that validates a proposed raw runtime value without falling back silently:

```java
public ConfigValidationResult validateRuntimeValue(String rawValue);
```

Create `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigValidationResult.java`:

```java
public final class ConfigValidationResult {
    private final boolean valid;
    private final String normalizedValue;
    private final String error;
    private final boolean sensitive;

    public static ConfigValidationResult ok(String normalizedValue, boolean sensitive);
    public static ConfigValidationResult invalid(String error, boolean sensitive);
}
```

Validation rules:

- `INT`, `LONG`, and `DOUBLE` must parse and fit configured ranges.
- `BOOLEAN` accepts only `true` or `false`, case-insensitive, and normalizes to lowercase.
- `STRING` trims whitespace and enforces non-blank / allowed values.
- Values that would fallback or clamp during normal read are invalid for runtime `set`.
- `normalizedValue` is the exact value stored by `ProductionConfig`.
- Sensitive keys are allowed if they are known schema keys, but all command output and audit details must use existing masking behavior.

Modify `foundation/src/main/java/com/javasleuth/foundation/config/ProductionConfig.java`:

- `setRuntimeConfig(key, value, source)` rejects forbidden keys as today.
- It rejects unknown keys using `SleuthConfigSchema.byKey(key) == null`.
- It rejects invalid values using `ConfigKey.validateRuntimeValue(value)`.
- It stores `ConfigValidationResult.getNormalizedValue()` rather than the raw string.
- `removeRuntimeConfig(...)` should still reject forbidden keys. Removing an unknown key can remain allowed as a cleanup convenience, because it cannot create an invalid runtime state.

Unknown runtime config behavior:

- Unknown keys are rejected in the first implementation.
- If plugin-owned runtime config is needed later, add an explicit plugin key registry rather than preserving arbitrary unknown core config writes.

Replace raw reads in built-in runtime paths:

- `core/src/main/java/com/javasleuth/core/enhancement/SleuthClassFileTransformer.java`
  - Use `SleuthConfigSchema.ENHANCEMENT_FAILURE_COOLDOWN_MS.read(config)`.
  - Use `SleuthConfigSchema.ENHANCEMENT_FAILURE_LOG_INTERVAL_MS.read(config)`.
- `core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java`
  - Use `SleuthConfigParser.parse(config.snapshot()).monitoring()` per invocation before queue creation.
  - Use `getWatchQueueCapacity()` and `isWatchDropOnFull()`.
- `core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java`
  - Use `getTraceQueueCapacity()` and `isTraceDropOnFull()`.
- `core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java`
  - Use the typed watch queue/drop settings if it reuses watch behavior.
- `core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java`
  - Use the typed trace or watch settings matching the existing semantic default.
- `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`
  - Build a typed snapshot once per `execute(...)` and render from it.

Add schema coverage for vmtool defaults:

- Add `vmtool.track.max.entries`, default `500`, range `1..100000`.
- Add `vmtool.track.class.limit`, default `50`, range `1..10000`.
- Add a new `VmToolConfig` model under `foundation/src/main/java/com/javasleuth/foundation/config/model/VmToolConfig.java`.
- Add `vmTool()` to `SleuthConfig` and parse it from `SleuthConfigParser`.
- `VmToolCommand` reads `typed.vmTool().getTrackMaxEntries()` and `getTrackClassLimit()`.

Update `core/src/main/java/com/javasleuth/core/command/impl/ConfigCommand.java`:

- `config set` reports schema validation failures directly.
- `config show` and `config status` should count and display only schema-known runtime overrides.
- Sensitive values remain masked through `SensitiveKeyMasker`.

### Phase 3: Cross-ClassLoader Contract Versioning

Modify `bootstrap/src/main/java/com/javasleuth/bootstrap/agent/AgentLifecycle.java`.

Add a bootstrap-visible contract method:

```java
public static int contractVersion() {
    return 1;
}
```

The value is a bootstrap bridge ABI contract version, not the Maven artifact version. It changes only when the reflective bootstrap/container contract becomes incompatible.

Modify `agent/src/main/java/com/javasleuth/agent/CrossClassLoaderFacade.java`.

Add constants:

```java
static final int MIN_BOOTSTRAP_CONTRACT_VERSION = 1;
static final int MAX_BOOTSTRAP_CONTRACT_VERSION = 1;
```

Add a diagnostic result type:

```java
static final class BootstrapContractCheck {
    enum Status {
        OK,
        MISSING_CLASS,
        MISSING_METHOD,
        BAD_RETURN_TYPE,
        INVOCATION_FAILED,
        INCOMPATIBLE_VERSION
    }

    boolean isOk();
    String userMessage();
}
```

Add:

```java
static BootstrapContractCheck verifyBootstrapContract();
```

Rules:

- Missing `AgentLifecycle` is `MISSING_CLASS`.
- Missing `contractVersion()` is `MISSING_METHOD`.
- A non-numeric return value is `BAD_RETURN_TYPE`.
- Reflection invocation errors are `INVOCATION_FAILED` and include the thrown type in debug output.
- A version outside the expected range is `INCOMPATIBLE_VERSION`.

Modify `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`:

- After appending the bootstrap bridge and before `tryBeginAttachOrZero()`, call `verifyBootstrapContract()`.
- Abort startup on non-OK with a message like:
  - `Bootstrap bridge contract mismatch: expected 1..1, found 2`
  - `Bootstrap bridge incomplete: missing AgentLifecycle.contractVersion()`
  - `Bootstrap bridge unavailable: missing com.javasleuth.bootstrap.agent.AgentLifecycle`

Modify core runtime checks:

- Add `AGENT_LIFECYCLE` to `core/src/main/java/com/javasleuth/core/agent/runtime/BootstrapBridge.java`.
- Add `bootstrapContractVersion()` and `describeContractStatus()`.
- In strict mode, `describeContractStatus()` must require bootstrap-loaded `AgentLifecycle` and compatible `contractVersion()`.
- Add the contract status to `StatusCommand` output next to bootstrap bridge status.

Update documentation:

- Modify `docs/dev/cross-classloader-contract.md` to list `AgentLifecycle.contractVersion() : int` and the compatibility rule.

### Phase 4: Lightweight CommandSpec

Create package `core/src/main/java/com/javasleuth/core/command/spec/`.

Add these classes:

- `CommandSpec`
- `SubcommandSpec`
- `ArgumentSpec`
- `OptionSpec`
- `ParsedCommand`
- `CommandSpecParser`
- `CommandHelpRenderer`
- `CommandSpecParseException`

Add interface `core/src/main/java/com/javasleuth/core/command/SpecBackedCommand.java`:

```java
public interface SpecBackedCommand {
    CommandSpec getSpec();
}
```

The existing `Command` and `StreamCommand` interfaces remain unchanged.

`CommandSpec` owns:

- command name
- description
- usage summary
- positional arguments
- options and aliases
- default values
- numeric ranges
- repeatability
- subcommands
- examples
- `CommandMeta` for role, danger, streamability, bootstrap requirements, capabilities, impact, and rate limits

Option parsing rules:

- Support `--flag` for boolean flags.
- Support `--key value` and `--key=value` for value options.
- Support short aliases such as `-n 10`.
- Unknown options fail with `E_ARGS_UNKNOWN`.
- Missing option values fail with `E_ARGS_MISSING`.
- Invalid typed values fail with `E_ARGS_INVALID`.
- Out-of-range values fail with `E_ARGS_RANGE`.
- Repeated non-repeatable options fail with `E_ARGS_DUPLICATE`.
- Repeatable options preserve input order.
- `-h`, `--help`, and `help` are handled by the spec parser and help renderer.

Pipeline integration:

- Extend `CommandDescriptor` with an optional `CommandSpec`.
- Add factory `CommandDescriptor.ofSpec(CommandSpec spec, Command command)` that uses `spec.getName()` and `spec.getMeta()`.
- Keep existing `CommandDescriptor.of(name, command, meta)` for non-migrated commands and plugins.
- Add optional `ParsedCommand parsedCommand` to `CommandContext` with `getParsedCommand()` and `withParsedCommand(ParsedCommand parsed)`.
- In `CommandPipeline.executePrechecked(...)`, if the entry has a spec, parse once before execution and pass a copied context with `parsedCommand`.
- In `CommandPipeline.executeStreamPrechecked(...)`, do the same and send generated help to the stream sink without installing instrumentation when help is requested.
- `PrecheckPipeline` can use the spec-backed `CommandMeta`, but authorization order stays unchanged.

Migrated command behavior:

- `watch`, `trace`, `monitor`, and `vmtool` implement `SpecBackedCommand`.
- Their manual parsing loops are replaced with reads from `CommandContextHolder.get().getParsedCommand()`.
- If a command is invoked directly in a unit test without pipeline context, it parses through its own spec as a fallback.
- Help output comes from `CommandHelpRenderer`, not private `getHelp()` string concatenation.

`watch` spec:

- Positionals: `class-pattern`, `method-pattern`.
- Options:
  - `-n`, `--count <num>`, default `100`, range `1..100000`
  - `-t`, `--timeout <sec>`, default `30`, range `1..86400`
  - `--loader <id>`
  - `--loader-id <id>` alias of `--loader`
  - `--loader-hash <id>` alias of `--loader`
  - `--first`
  - `--unsafe-first` alias of `--first`
  - `--expr <fields>`
  - `--condition <condition>`, repeatable
  - `--bg`
  - `--no-params`
  - `--no-return`
  - `--no-exception`

`trace` spec:

- Positionals: `class-pattern`, `method-pattern`.
- Options:
  - `-d`, `--depth <num>`, default `10`, range `1..1000`
  - `-n`, `--count <num>`, default `20`, range `1..100000`
  - `-t`, `--timeout <sec>`, default `30`, range `1..86400`
  - `--loader <id>` with existing aliases
  - `--first` with `--unsafe-first` alias
  - `--expr <fields>`
  - `--condition <condition>`, repeatable
  - `--bg`
  - `--sample` and `--sample-rate` remain explicit removed options that produce the current removed-option error.

`monitor` spec:

- Positionals: `class-pattern`, `method-pattern`.
- Options:
  - `-i`, `--interval <ms>`, default `5000`, range `1..86400000`
  - `-n`, `--count <num>`, default `10`, range `1..100000`
  - `--limit <num>`, default `50`, range `1..10000`
  - `--bg`

`vmtool` spec:

- Subcommands: `track`, `stop`, `tracks`, `instances`, `inspect`, `invoke`, `invoke-static`, `invokestatic`, `histogram`.
- `invoke-static` and `invokestatic` share one subcommand spec.
- `invoke` and `invoke-static` keep `CommandMeta` admin subcommand roles and dangerous confirmation behavior.
- Variable method arguments are represented as trailing positionals until an option token is encountered.
- Existing `--confirm <token>` and `--confirm=<token>` remain supported by the confirmation manager.

Help integration:

- `HelpCommand` continues listing all commands.
- If a command has a spec, its summary uses `spec.getDescription()`.
- A later enhancement may add `help <command>`; this first pass only guarantees `<command> --help` and stream help are generated from spec.

## File Impact

Create:

- `core/src/main/java/com/javasleuth/core/command/CancellationToken.java`
- `core/src/main/java/com/javasleuth/core/command/CancellationTokenSource.java`
- `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigValidationResult.java`
- `foundation/src/main/java/com/javasleuth/foundation/config/model/VmToolConfig.java`
- `core/src/main/java/com/javasleuth/core/command/SpecBackedCommand.java`
- `core/src/main/java/com/javasleuth/core/command/spec/CommandSpec.java`
- `core/src/main/java/com/javasleuth/core/command/spec/SubcommandSpec.java`
- `core/src/main/java/com/javasleuth/core/command/spec/ArgumentSpec.java`
- `core/src/main/java/com/javasleuth/core/command/spec/OptionSpec.java`
- `core/src/main/java/com/javasleuth/core/command/spec/ParsedCommand.java`
- `core/src/main/java/com/javasleuth/core/command/spec/CommandSpecParser.java`
- `core/src/main/java/com/javasleuth/core/command/spec/CommandHelpRenderer.java`
- `core/src/main/java/com/javasleuth/core/command/spec/CommandSpecParseException.java`

Modify:

- `core/src/main/java/com/javasleuth/core/command/pipeline/CommandExecutionEngine.java`
- `core/src/main/java/com/javasleuth/core/command/CommandPipeline.java`
- `core/src/main/java/com/javasleuth/core/command/pipeline/PrecheckPipeline.java`
- `core/src/main/java/com/javasleuth/core/command/CommandContext.java`
- `core/src/main/java/com/javasleuth/core/command/CommandDescriptor.java`
- `core/src/main/java/com/javasleuth/core/command/CommandRegistry.java`
- `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- `core/src/main/java/com/javasleuth/core/command/JobManager.java`
- `core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/ConfigCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java`
- `core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java`
- `core/src/main/java/com/javasleuth/core/enhancement/SleuthClassFileTransformer.java`
- `core/src/main/java/com/javasleuth/core/agent/runtime/BootstrapBridge.java`
- `foundation/src/main/java/com/javasleuth/foundation/config/ProductionConfig.java`
- `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigKey.java`
- `foundation/src/main/java/com/javasleuth/foundation/config/schema/SleuthConfigSchema.java`
- `foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfig.java`
- `foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfigParser.java`
- `foundation/src/main/java/com/javasleuth/foundation/config/model/PerformanceConfig.java`
- `bootstrap/src/main/java/com/javasleuth/bootstrap/agent/AgentLifecycle.java`
- `agent/src/main/java/com/javasleuth/agent/CrossClassLoaderFacade.java`
- `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`
- `docs/dev/cross-classloader-contract.md`
- `docs/usage/commands.md`

## Test Plan

### Execution Pool and Cancellation Tests

Add or extend:

- `core/src/test/java/com/javasleuth/command/CommandExecutionEngineIsolationTest.java`
- `core/src/test/java/com/javasleuth/command/CommandPipelineStreamExecutionTest.java`
- `core/src/test/java/com/javasleuth/command/JobManagerConcurrencyTest.java`

Required assertions:

- A saturated stream executor does not prevent a short command from completing.
- A saturated short executor reports the short queue-full message.
- A saturated stream executor reports the stream queue-full message.
- A stream timeout cancels the token and interrupts the worker.
- `JobManager.stop(...)` cancels the same token visible to job command code.
- Long-running command loops exit promptly when the token is cancelled.

Run:

```bash
mvn -pl core -am -Dtest=CommandExecutionEngineIsolationTest,CommandPipelineStreamExecutionTest,JobManagerConcurrencyTest test
```

### Config Schema Tests

Add or extend:

- `core/src/test/java/com/javasleuth/config/ConfigSemanticsTest.java`
- `core/src/test/java/com/javasleuth/config/SleuthConfigParserTest.java`
- `core/src/test/java/com/javasleuth/config/ProductionConfigRuntimeValidationTest.java`

Required assertions:

- `config set monitoring.watch.queue.capacity 0` is rejected before storage.
- `config set monitoring.trace.drop.on.full maybe` is rejected before storage.
- Runtime boolean values normalize to `true` or `false` only.
- Unknown runtime keys are rejected.
- Sensitive known keys remain masked in command output.
- Typed snapshots reflect valid runtime overrides on the next command invocation.
- VmTool defaults parse from schema and reject invalid runtime overrides.

Run:

```bash
mvn -pl core -am -Dtest=ConfigSemanticsTest,SleuthConfigParserTest,ProductionConfigRuntimeValidationTest test
```

### Cross-ClassLoader Contract Tests

Add or extend:

- `core/src/test/java/com/javasleuth/bootstrap/agent/AgentLifecycleTest.java`
- `core/src/test/java/com/javasleuth/agent/CrossClassLoaderReflectionContractTest.java`
- `agent/src/test/java/com/javasleuth/agent/CrossClassLoaderFacadeContractTest.java`

Required assertions:

- `AgentLifecycle.contractVersion()` exists and returns `1`.
- The thin-agent facade reports OK for version `1`.
- Missing method diagnostics mention `contractVersion()`.
- Incompatible version diagnostics mention expected and found versions.
- Existing lifecycle reflection method contract tests still pass.

Run:

```bash
mvn -pl agent,core -am -Dtest=CrossClassLoaderFacadeContractTest,CrossClassLoaderReflectionContractTest,AgentLifecycleTest test
```

### CommandSpec Tests

Add:

- `core/src/test/java/com/javasleuth/command/spec/CommandSpecParserTest.java`
- `core/src/test/java/com/javasleuth/command/spec/CommandHelpRendererTest.java`
- `core/src/test/java/com/javasleuth/command/BuiltinCommandSpecTest.java`

Extend:

- `core/src/test/java/com/javasleuth/command/CommandArgsValidationTest.java`
- `core/src/test/java/com/javasleuth/core/command/impl/TraceCommandSampleOptionRemovedTest.java`

Required assertions:

- `--key=value`, `--key value`, and short aliases parse to the same option.
- Unknown options fail with `E_ARGS_UNKNOWN`.
- Missing values fail with `E_ARGS_MISSING`.
- Invalid integers fail with `E_ARGS_INVALID` instead of silently using defaults.
- Out-of-range values fail with `E_ARGS_RANGE`.
- Repeatable `--condition` preserves order.
- `watch --help`, `trace --help`, `monitor --help`, and `vmtool --help` are rendered from specs.
- Stream help does not install instrumentation.
- `vmtool invoke` and `vmtool invoke-static` retain admin/danger confirmation metadata.
- `trace --sample` and `trace --sample-rate` keep the current removed-option error.

Run:

```bash
mvn -pl core -am -Dtest=CommandSpecParserTest,CommandHelpRendererTest,BuiltinCommandSpecTest,CommandArgsValidationTest,TraceCommandSampleOptionRemovedTest test
```

### Full Verification

Run before completion:

```bash
mvn test
```

If full `mvn test` is too slow in the working environment, run at minimum:

```bash
mvn -pl core -am test
mvn -pl agent -am test
```

## Migration and Compatibility

- Existing `Command` and `StreamCommand` remain source-compatible.
- Existing `CommandDescriptor.of(name, command, meta)` remains supported.
- Existing plugin commands do not need specs.
- Existing `performance.command.executor.*` keys continue to control short command execution.
- Stream executor keys are additive.
- Runtime `config set` becomes stricter by design; invalid values that previously stored successfully are now rejected.
- Command parser behavior becomes stricter for migrated commands; invalid numbers produce explicit errors instead of silent fallback.
- The bootstrap contract version starts at `1`; do not tie it to Maven version `1.0.0`.

## Acceptance Criteria

- With all stream executor workers occupied, a short `status`-like command can still execute on the short pool.
- Foreground stream timeout and `jobs stop` both make command code observe cancellation through the same token API.
- Runtime config writes cannot store invalid typed values for schema-known keys.
- Built-in command runtime reads named in this spec no longer call raw `config.getInt/getBoolean/getLong` except where a schema key intentionally does not exist.
- Thin-agent startup reports contract mismatch, missing class, and missing method distinctly.
- `StatusCommand` includes bootstrap contract status.
- `watch`, `trace`, `monitor`, and `vmtool` expose specs and generate help from specs.
- Migrated commands have consistent invalid argument errors.
- The targeted tests and full verification commands pass.

## Implementation Order

1. Add cancellation token and split execution pools.
2. Enforce schema validation for runtime config and replace raw config reads.
3. Add bootstrap contract versioning and diagnostics.
4. Add `CommandSpec` infrastructure without migrating commands.
5. Migrate `watch`, `trace`, `monitor`, and `vmtool` to specs.
6. Update docs and run full verification.

This order keeps runtime safety fixes first and delays the broader parser migration until the core execution/config behavior is stable.

## Self-Review

- Spec coverage: all four requested review items are covered by phases 1 through 4.
- Draft marker scan: no incomplete sections remain.
- Scope check: one total spec is intentional, with independently testable phases.
- Ambiguity check: unknown runtime config behavior, stream executor key names, contract version range, and migrated command parser semantics are explicit.
