# Command Processor Assembly Split

## Summary

`CommandProcessorFactory` is currently the command server composition root and also the default
dependency factory. Its main `createComponents(...)` method validates inputs, creates default
runtime services, configures `ServerBootstrapper`, builds the client executor, loads command
providers, creates the registry and pipeline, wires server lifecycle components, decides resource
ownership, and builds the close order for owned resources.

This spec splits that assembly into focused package-level factories:

- `RuntimeServicesFactory`
- `CommandSubsystemFactory`
- `ServerSubsystemFactory`
- `ResourceCloser`

`CommandProcessorFactory` remains the public facade, but delegates to these focused builders. The
canonical input stays `CommandProcessorFactoryRequest`; long positional overloads are deprecated
and reduced to compatibility wrappers.

## Problem

`core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java` has become a dense
startup assembly center:

- Lines near the public overloads duplicate long positional argument lists.
- Default service creation and ownership flags are mixed with command and server assembly.
- Resource close order is embedded inside the same method that creates the resources.
- `ShutdownCoordinator`, `CommandProcessorOwnedResources`, `AttachSessionContext`, and
  `SleuthAgentServices` all participate in shutdown, but the ownership boundary is not obvious
  from the factory code.
- The attach runtime path already owns many services through `SleuthAgentServices`; the standalone
  `CommandProcessor` path creates its own defaults in `CommandProcessorFactory`.

The behavior is mostly correct today, but the structure makes future changes risky:

- Adding a new service requires touching the factory constructor list, ownership flags, provider
  context, subsystem constructors, and close order.
- Adding a new shutdown step can accidentally double-close attach-owned services or skip
  standalone-owned services.
- Tests are currently forced to inspect `CommandProcessor` internals or rely on background thread
  names instead of testing smaller assembly units.

## Goals

- Keep `CommandProcessor` as a thin lifecycle facade.
- Keep `CommandProcessorFactoryRequest` as the canonical public composition input.
- Move standalone default service creation and ownership metadata into `RuntimeServicesFactory`.
- Move command registry and pipeline wiring into `CommandSubsystemFactory`.
- Move server lifecycle wiring and executor creation into `ServerSubsystemFactory`.
- Move owned-resource close list construction into `ResourceCloser`.
- Preserve current runtime behavior, shutdown order, audit logging, command loading, and server
  executor settings.
- Make attach-owned services clearly distinct from factory-owned standalone defaults.
- Add focused tests for each new assembly boundary.

## Non-Goals

- Do not redesign command execution, command parsing, plugin loading, or authorization.
- Do not change command protocol behavior.
- Do not change `AttachSessionContext` ownership semantics.
- Do not replace `SleuthAgentServices`; it remains the attach-scope runtime services owner.
- Do not remove public long positional overloads in the first implementation. Deprecate them and
  route them through `CommandProcessorFactoryRequest` instead.
- Do not introduce a dependency injection framework.

## Approach Considered

### Recommended: Package-Level Assembly Objects

Create small package-private value objects and factories in `com.javasleuth.core.command`.

Advantages:

- Keeps the refactor local to the command module.
- Avoids public API churn.
- Keeps assembly code testable without making every intermediate type public.
- Matches the current no-framework style.

Trade-off:

- Adds several small classes. This is acceptable because each class owns a clear lifecycle boundary.

### Alternative: Move Everything Into `SleuthAgentServices`

Let `SleuthAgentServices` own all command runtime services and pass one services object to the
factory.

Advantages:

- One apparent attach/runtime service container.

Trade-off:

- Blurs attach runtime and standalone `CommandProcessor` usage.
- Forces standalone tests and launcher integration paths through an attach-oriented container.
- Makes `SleuthAgentServices` a broader god object.

### Alternative: Keep One Factory With Private Methods

Break the large method into private methods inside `CommandProcessorFactory`.

Advantages:

- Minimal file churn.

Trade-off:

- Does not clarify ownership boundaries.
- Still keeps unrelated lifecycle responsibilities in one class.
- Does little to reduce overload and future-change risk.

## Target Architecture

### Public Facade

`CommandProcessorFactory` remains public and small:

```java
public final class CommandProcessorFactory {
    public static CommandProcessor create(CommandProcessorFactoryRequest request);
    public static CommandProcessorComponents createComponents(CommandProcessorFactoryRequest request);
}
```

Existing `createDefault(...)`, `create(...)`, and `createComponents(...)` positional overloads remain
temporarily for source compatibility. They should be annotated `@Deprecated` when they include more
than the required `Instrumentation`, `SleuthClassFileTransformer`, and optional `shutdownHook`.

Internal callers should use:

```java
CommandProcessorFactory.create(
    CommandProcessorFactoryRequest.builder(inst, transformer)
        .withShutdownHook(shutdownHook)
        .withConfig(config)
        .withAuditLogger(auditLogger)
        .build()
);
```

### Runtime Services

Create `core/src/main/java/com/javasleuth/core/command/RuntimeServicesFactory.java`.

Responsibility:

- Validate required instrumentation and transformer.
- Resolve default `ProductionConfig`.
- Create or reuse:
  - `AuditLogger`
  - `AuthenticationManager`
  - `AuthorizationManager`
  - `DangerousCommandConfirmationManager`
  - `ClientSessionRegistry`
  - `MetricsCollector`
  - `JobManager`
  - `VmToolSessionRegistry`
  - `PerformanceOptimizer`
  - `SleuthSpyDispatcher`
  - `EnhancementSessionRegistry`
- Record ownership for resources created by this factory.
- Perform the best-effort initial audit dropped metric recording.

It returns a package-private value object:

```java
final class RuntimeServices {
    final Instrumentation instrumentation;
    final SleuthClassFileTransformer transformer;
    final Runnable shutdownHook;
    final ProductionConfig config;
    final SleuthConfig typedConfig;
    final AuditLogger auditLogger;
    final InputValidator inputValidator;
    final AuthenticationManager authenticationManager;
    final AuthorizationManager authorizationManager;
    final DangerousCommandConfirmationManager dangerousConfirm;
    final ClientSessionRegistry clientSessionRegistry;
    final MetricsCollector metricsCollector;
    final JobManager jobManager;
    final VmToolSessionRegistry vmToolSessionRegistry;
    final PerformanceOptimizer performanceOptimizer;
    final SleuthSpyDispatcher spyDispatcher;
    final EnhancementSessionRegistry enhancementSessionRegistry;
    final ResourceOwnership ownership;
}
```

`RuntimeServicesFactory` is scoped to command processor standalone/default wiring. It must not
replace `SleuthAgentServices`. When attach code injects services through `CommandProcessorFactoryRequest`,
the ownership flags are false and `ResourceCloser` must not close attach-owned services.

### Command Subsystem

Create `core/src/main/java/com/javasleuth/core/command/CommandSubsystemFactory.java`.

Responsibility:

- Build `CommandProviderContext`.
- Create `BuiltinCommandProvider`.
- Load providers through `CommandProviderLoader`.
- Create `CommandRegistry`.
- Create `CommandPipeline`.

It returns:

```java
final class CommandSubsystem {
    final CommandRegistry registry;
    final CommandPipeline pipeline;
}
```

The provider loader should continue to use `CommandProcessorFactory.class.getClassLoader()` or an
equivalent stable classloader reference. Plugin classloader ownership remains inside
`CommandRegistry.shutdown()`.

### Server Subsystem

Create `core/src/main/java/com/javasleuth/core/command/ServerSubsystemFactory.java`.

Responsibility:

- Create `AtomicBoolean running`.
- Create `AtomicLong commandCounter`.
- Create the bounded `ThreadPoolExecutor` from `SleuthConfig`.
- Configure `ServerBootstrapper` logging provider and `JobManager`.
- Create `ClientSessionIndex`.
- Create `CommandClientHandler`.
- Create `ConnectionAcceptor`.
- Create `ShutdownCoordinator`.

It returns:

```java
final class ServerSubsystem {
    final AtomicBoolean running;
    final AtomicLong commandCounter;
    final ThreadPoolExecutor clientExecutor;
    final ClientSessionIndex sessionIndex;
    final CommandClientHandler clientHandler;
    final ServerBootstrapper bootstrapper;
    final ConnectionAcceptor acceptor;
    final ShutdownCoordinator shutdownCoordinator;
}
```

The thread pool settings must remain:

- core size: `typedConfig.performance().getThreadPoolCoreSize()`
- max size: `typedConfig.performance().getThreadPoolMaxSize()`
- keep alive: `60L` seconds
- queue: `LinkedBlockingQueue<>(typedConfig.server().getExecutorQueueCapacity())`
- thread factory: `SleuthThreadFactory.daemon("sleuth-client")`
- rejection handler: `ThreadPoolExecutor.CallerRunsPolicy`

### Resource Closer

Create `core/src/main/java/com/javasleuth/core/command/ResourceCloser.java`.

Responsibility:

- Build the `AutoCloseable` for resources created by `RuntimeServicesFactory`.
- Preserve current reverse-close behavior from `CommandProcessorOwnedResources`.
- Keep close operations best-effort and idempotent.
- Make close order explicit and testable.

The close list should preserve the current effective order:

1. `EnhancementSessionRegistry.closeAll("shutdown")`
2. `JobManager.shutdown("shutdown")`
3. `ClientSessionRegistry.shutdown("shutdown")`
4. `VmToolSessionRegistry.shutdown(instrumentation, transformer, "shutdown")`
5. `PerformanceOptimizer.close()`
6. `DangerousCommandConfirmationManager.close()`
7. `AuthenticationManager.close()`
8. `AuditLogger.close()`

Because `CommandProcessorOwnedResources` closes in reverse order, `ResourceCloser` should either add
dependencies in the existing dependency-first order or replace it with a clearer forward-order
closer. The final observable close order must match the list above.

`CommandProcessorOwnedResources` can remain as the composite implementation if it is renamed or
delegated by `ResourceCloser`. The important change is that `CommandProcessorFactory` no longer
constructs the list inline.

## Assembly Flow

`CommandProcessorFactory.createComponents(request)` should become:

```java
RuntimeServices services = RuntimeServicesFactory.create(request);
CommandSubsystem commandSubsystem = CommandSubsystemFactory.create(services);
ServerSubsystem serverSubsystem = ServerSubsystemFactory.create(services, commandSubsystem);
AutoCloseable ownedResources = ResourceCloser.forOwnedResources(services);
logInitializedBestEffort(services.auditLogger, commandSubsystem.registry);
return CommandProcessorComponents.from(services, commandSubsystem, serverSubsystem, ownedResources);
```

`CommandProcessorComponents` can keep its current constructor, but adding a package-private static
factory such as `from(...)` is preferred so the final assembly call is readable.

## API Migration

### Keep

- `new CommandProcessor(Instrumentation, SleuthClassFileTransformer)`
- `new CommandProcessor(Instrumentation, SleuthClassFileTransformer, Runnable)`
- `new CommandProcessor(CommandProcessorComponents)`
- `CommandProcessorFactory.create(CommandProcessorFactoryRequest)`
- `CommandProcessorFactory.createComponents(CommandProcessorFactoryRequest)`

### Deprecate

Long positional overloads on `CommandProcessorFactory` and `CommandProcessor` that accept config,
security services, registries, metrics, job manager, vmtool registry, performance optimizer, or spy
dispatcher should be marked deprecated when feasible. They remain wrappers around
`CommandProcessorFactoryRequest`.

### Migrate Internal Callers

`AttachSessionContext` already uses `CommandProcessorFactoryRequest` and should continue doing so.
Any tests or internal code still using long positional overloads should move to the builder unless
the test explicitly protects compatibility.

## Lifecycle Rules

### Standalone `CommandProcessor`

When dependencies are omitted:

- `RuntimeServicesFactory` creates standalone defaults.
- Ownership flags are true for created services.
- `ResourceCloser` returns a non-empty owned resource closer.
- `CommandProcessor.shutdownGracefully(...)` and `emergencyShutdown()` close owned resources after
  `ShutdownCoordinator` executes.

### Attach Runtime

When `AttachSessionContext` injects runtime-owned dependencies:

- Ownership flags are false for injected services.
- `ResourceCloser` returns null or an empty closer.
- `CommandProcessor.shutdownForDetach()` stops command server resources.
- `AttachSessionContext.close()` remains responsible for attach-owned registries, spy cleanup,
  enhancer rollback, transformer removal, and `SleuthAgentServices.close()`.

### Mixed Injection

If a request injects some services and omits others:

- Only omitted services are owned by `RuntimeServicesFactory`.
- `ResourceCloser` closes only owned services.
- Injected services are never closed by command processor owned resources.

This preserves current behavior and gives tests a clear contract.

## Testing Strategy

### Unit Tests

Add focused tests under `core/src/test/java/com/javasleuth/command/`:

- `RuntimeServicesFactoryTest`
  - creates defaults when request omits optional services
  - reuses injected services
  - marks only omitted services as owned
  - rejects null instrumentation and transformer

- `ResourceCloserTest`
  - closes owned resources in the documented order
  - does not close injected resources
  - is idempotent when `close()` is called multiple times
  - continues closing later resources when one close operation throws

- `CommandSubsystemFactoryTest`
  - creates registry and pipeline from runtime services
  - registry contains built-in commands including `help`
  - plugin classloader cleanup remains owned by `CommandRegistry.shutdown()`

- `ServerSubsystemFactoryTest`
  - creates bounded executor queue from `server.executor.queue.capacity`
  - uses `CallerRunsPolicy`
  - creates server bootstrapper, acceptor, client handler, and shutdown coordinator

### Regression Tests

Keep and adapt existing tests:

- `CommandProcessorFactoryRequestTest`
- `CommandProcessorOwnedResourcesCloseTest`
- `CommandProcessorExecutorQueueTest`
- `CommandProcessorMaxConnectionsTest`
- `CommandProcessorShutdownHookTest`

These should continue to exercise behavior through public `CommandProcessor` or
`CommandProcessorFactory` APIs, not the private internals of the new factories unless the test is
specifically about factory boundaries.

### Verification Commands

Run targeted tests first:

```bash
mvn -pl core -Dtest=CommandProcessorFactoryRequestTest,CommandProcessorOwnedResourcesCloseTest,CommandProcessorExecutorQueueTest,CommandProcessorMaxConnectionsTest,CommandProcessorShutdownHookTest test
```

Then run the broader core test suite:

```bash
mvn -pl core test
```

If the reactor requires upstream modules in this repository, use:

```bash
mvn test
```

## Rollout Plan

1. Add tests for ownership and close ordering before moving code.
2. Extract `ResourceCloser` and keep `CommandProcessorOwnedResources` behavior unchanged.
3. Extract `RuntimeServicesFactory` and route `CommandProcessorFactory` through it.
4. Extract `CommandSubsystemFactory`.
5. Extract `ServerSubsystemFactory`.
6. Add `CommandProcessorComponents.from(...)` if it improves assembly readability.
7. Deprecate long positional overloads and migrate internal call sites to request builder.
8. Run targeted command processor tests and the full core test suite.

## Acceptance Criteria

- `CommandProcessorFactory.createComponents(CommandProcessorFactoryRequest)` is a short orchestration
  method rather than a large assembly body.
- Default standalone `CommandProcessor` creation still starts and shuts down without leaking
  `sleuth-audit-logger` or `sleuth-session-cleanup` threads.
- Injected attach-scope services are not closed by `CommandProcessor` owned resources.
- Executor queue capacity and rejection policy remain unchanged.
- Registry and pipeline behavior remain unchanged.
- Server bind, max connection rejection, shutdown hook removal, and detach shutdown behavior remain
  unchanged.
- All new factories are package-private unless a public API need appears.
- Long positional overloads no longer contain assembly logic.

## Risks

- Close order regressions can create thread leaks or classloader retention. Mitigation: implement
  `ResourceCloserTest` before extraction.
- Mixed injection ownership can accidentally close caller-owned services. Mitigation: model
  ownership explicitly in `ResourceOwnership` and test injected services.
- Plugin loading can regress if the provider loader classloader changes. Mitigation: keep the
  existing classloader source and test built-in provider loading.
- Over-deprecating constructors can disturb test and launcher code. Mitigation: deprecate wrappers
  without removing them in this change.
