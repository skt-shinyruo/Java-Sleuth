# Attach-Scope Services & Runtime Resource Ownership (Issue #4/#9) Design

## Background

Java-Sleuth aims to support **detach → re-attach** within the same JVM. The ideal lifecycle model is:

- **Each attach creates exactly one `SleuthAgentRuntime`**
- All resources created/used during this attach are **owned by the runtime**
- Detach is a **single operation**: `runtime.close()` (idempotent)
- After detach, the JVM should not retain:
  - `sleuth-*` threads / executors / scheduled tasks
  - `com.javasleuth:type=*` JMX MBeans
  - stale references to config/loggers/authorizers, preventing classloader GC

Current implementation still uses multiple **global singletons** (static `getInstance()`), and shutdown depends on
`ShutdownCoordinator` / `SleuthAgentEntrypointSupport` / test-only resets “hand-picking” shutdown calls. This creates:

- High maintenance risk (new background resources are easy to forget to register)
- Detach correctness that depends on *scattered* best-effort logic
- Resource leaks (threads/MBeans) that keep the isolated classloader alive

Related issues:

- #4: Too many global singletons; runtime boundary is not objectized
- #9: Thread/executor/JMX ownership is scattered; detach/re-attach reliability suffers


## Goals (Definition of Done)

1. Detach external contract is a single, testable entry: **`SleuthAgentRuntime.close()`**.
2. All thread/executor/JMX/MBean resources created during attach are **attach-scope** and are closed by runtime.
3. `ShutdownCoordinator` no longer “names” singletons to shut down.
4. Multi-round attach/close in the same JVM does not accumulate:
   - `sleuth-*` threads
   - `com.javasleuth:type=*` MBeans
5. Adding a new component with threads/caches has a single ownership path: **runtime/services field + `close()`**.


## Non-Goals

- Preserve binary compatibility for existing third-party plugins (breaking change accepted).
- Implement a full “agent restart” model; focus is detach → re-attach correctness.


## Proposed Architecture

### 1) Runtime is the SSOT (Arthas-style `destroy()` closure)

Introduce an attach-scope services container:

- `SleuthAgentRuntime` remains the per-attach resource container.
- Add `SleuthAgentServices` which groups previously-global services and provides `close()` to release them.

This mirrors Arthas’s pattern:

- fields hold all resources
- one destroy/close method performs deterministic, idempotent cleanup


### 2) Remove global singletons; make them attach-scope

Convert the following to **non-singleton, attach-scope** objects (no `getInstance()`):

- `ProductionConfig`
- `AuditLogger`
- `AuthenticationManager`
- `DangerousCommandConfirmationManager`
- `PerformanceOptimizer`
- `MemoryOptimizer`

Each must:

- expose an explicit constructor with required dependencies
- implement `AutoCloseable` (or have `close()` called by the owner)
- create and own their threads/executors/MBeans
- release them in `close()` idempotently


### 3) Shutdown order & ownership

`SleuthAgentRuntime.close()` becomes the only detach entry. Proposed shutdown order:

1. `commandProcessor.shutdownForDetach()`
   - stops command networking and the server loop
   - triggers `ShutdownCoordinator` to close command-server resources (socket/executor/pipeline/registry/metrics)
2. Shutdown runtime registries/jobs/vmtool sessions (existing best-effort logic)
3. Remove enhancers and retransform previously enhanced classes (existing best-effort logic)
4. Remove transformer
5. Close attach-scope services:
   - `MemoryOptimizer.close()` **before** `PerformanceOptimizer.close()` (scheduler must stop before perf cache/executors)
   - `AuditLogger.close()` flushes/joins thread and closes writers

### 4) `ShutdownCoordinator` responsibility shrinks

`ShutdownCoordinator` must **only** manage command-server resources:

- server socket close
- client executor shutdown
- pipeline shutdown
- registry shutdown (including `AutoCloseable` commands, and plugin classloader close)
- metrics collector shutdown

It must no longer:

- shutdown any singleton instance
- shutdown `AuditLogger` / `AuthenticationManager` / `PerformanceOptimizer` / `MemoryOptimizer`

Those are runtime-owned and are closed by `SleuthAgentRuntime.close()`.


## Plugin API (Breaking Change)

### Rationale

If plugins can only implement `getCommands()` without context, they tend to reach for static singletons. To enforce
attach-scope ownership, plugins must be provided a runtime context and use it for dependencies.

### Change

Add `CommandProviderContext` and update `CommandProvider`:

- `Map<String, Command> getCommands(CommandProviderContext ctx);`
- `default Map<String, CommandMeta> getCommandMeta()` unchanged

`CommandRegistry` will call `provider.getCommands(ctx)` when registering commands.


## Removing static `PerformanceOptimizer` usage

The following code paths currently use static `PerformanceOptimizer.*`:

- `core/.../pipeline/CacheInterceptor`
- `core/.../impl/HeapDumpCommand`
- `foundation/.../MemoryOptimizer` (static calls to clear caches)

All of these must be changed to use an attach-scope `PerformanceOptimizer` instance injected via constructors.

Also, utility-only methods (e.g. `formatDuration`) should be moved to a pure util type to avoid accidentally triggering
optimizer initialization by referencing its class.


## Testing / Verification Strategy

### Thread leak tests

Add a new core test that loops N times:

- create runtime (fake instrumentation)
- close runtime
- assert there are no `sleuth-*` threads remaining (or at least count does not grow)

### MBean leak tests

After each close, verify the platform MBeanServer does not have these registered:

- `com.javasleuth:type=PerformanceOptimizer`
- `com.javasleuth:type=MemoryOptimizer`
- `com.javasleuth:type=MetricsCollector`

### Profiler scheduler leak test

Ensure `ProfilerCommand` (which starts `sleuth-profiler-sampler`) is closed via `CommandRegistry.shutdown()` /
runtime close. Verify the sampler thread does not remain after shutdown.


## Migration Notes

- This change intentionally breaks existing plugins; they must migrate to the new context-based API.
- Test-only helper `SleuthTestState.resetAll()` should be simplified: it should no longer need to reset singletons.

