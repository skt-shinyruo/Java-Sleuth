# Unified Enhancement Session Registry

## Summary

Java-Sleuth currently lets multiple instrumentation commands own their active sessions independently. `watch`, `trace`, `monitor`, `stack`, and `tt` keep command-local `activeSessions` maps, while `vmtool track` has its own `VmToolSessionRegistry`. `reset` only knows how to stop jobs, stop vmtool tracks, clear legacy bootstrap interceptors, remove all transformer enhancers, and retransform previously enhanced classes.

This spec introduces an attach-scope `EnhancementSessionRegistry` as the single lifecycle registry for all command-triggered bytecode enhancement sessions. Every enhancement command registers a session handle with the registry after successful listener/enhancer/retransform setup. `reset`, `status`, `stop`, client disconnect cleanup, and detach all close and report active sessions through the registry before falling back to low-level transformer/dispatcher cleanup.

The implementation covers all affected commands in one design:

- `watch`
- `trace`
- `monitor`
- `stack`
- `tt`
- `vmtool track`
- `reset`
- `status`
- `stop` / attach detach shutdown

The work is divided into multiple implementation plans for review and execution, but the target behavior is one complete design.

## Problem

The current lifecycle model is split across several scopes:

- `WatchCommand`, `TraceCommand`, and `MonitorCommand` own command-local `ConcurrentHashMap<String, *Session>` registries.
- `StackTraceLiteEngine` and `TtRecordEngine` own their own active session maps.
- `VmToolSessionRegistry` owns vmtool tracks separately.
- `ClientSession` owns disconnect cleanup callbacks, but only per connected client.
- `ResetCommand` knows about `JobManager`, `VmToolSessionRegistry`, legacy interceptor reset, and transformer fallback cleanup, but it does not know the command-local session maps.
- `StatusCommand` reports dispatcher and legacy interceptor counts, but not unified command session state.

This creates these risks:

- `reset` can remove enhancers while command-local sessions still believe they are active.
- Foreground streaming commands can continue waiting until timeout after reset because their polling loops have no registry-level cancellation signal.
- `status` can show listener counts without explaining which command sessions own them.
- `stop` and detach rely on broad fallback cleanup instead of first closing explicit session handles.
- New instrumentation commands can copy the same local-session pattern and grow the gap.

## Goals

- Introduce one attach-scope registry for all enhancement session lifecycle handles.
- Make session close idempotent and best-effort.
- Route `reset`, `status`, client disconnect, `stop`, and detach through the registry.
- Preserve existing command behavior and output unless the current behavior is lifecycle leakage.
- Keep command-specific event formatting, queues, record stores, and business logic inside each command or engine.
- Keep low-level transformer, dispatcher, vmtool, and legacy interceptor cleanup as fallback safety nets.
- Add tests that prove active sessions are closed, counted, and removed from the registry across all lifecycle paths.

## Non-Goals

- Do not redesign the bytecode enhancer implementations.
- Do not replace `SleuthSpyDispatcher` as the listener dispatcher.
- Do not remove `VmToolSessionRegistry` immediately; it still owns vmtool instance tracking data and APIs.
- Do not introduce a public remote management API for sessions beyond existing commands unless needed for `status`.
- Do not change the semantics of watch/trace/monitor/stack/tt event collection except for prompt cancellation on reset/stop/detach.

## Target Architecture

### Attach-Scope Ownership

`AttachSessionContext` creates one `EnhancementSessionRegistry` per attach lifecycle. It is passed through:

- `CommandProcessorFactoryRequest`
- `CommandProcessorFactory`
- `CommandProviderContext`
- `BuiltinCommandProvider`
- affected command constructors

The registry has the same lifecycle level as:

- `JobManager`
- `ClientSessionRegistry`
- `VmToolSessionRegistry`
- `SleuthSpyDispatcher`
- `SleuthClassFileTransformer`

### Registry Package

Create:

- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionRegistry.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionKind.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionDescriptor.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionHandle.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionSnapshot.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionCloseSummary.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionCloser.java`

### Core API

The registry API should stay small:

```java
public final class EnhancementSessionRegistry {
    public EnhancementSessionHandle register(
        EnhancementSessionDescriptor descriptor,
        EnhancementSessionCloser closer
    );

    public boolean close(String sessionId, String reason);

    public int closeByClient(String clientId, String reason);

    public EnhancementSessionCloseSummary closeAll(String reason);

    public List<EnhancementSessionSnapshot> list();

    public Map<EnhancementSessionKind, Integer> countByKind();

    public int size();
}
```

`EnhancementSessionHandle` should be a lightweight close token:

```java
public interface EnhancementSessionHandle extends AutoCloseable {
    String getSessionId();
    EnhancementSessionKind getKind();
    boolean isClosed();
    void close(String reason);
    @Override
    default void close() {
        close("close");
    }
}
```

`EnhancementSessionCloser` is command-owned cleanup:

```java
@FunctionalInterface
public interface EnhancementSessionCloser {
    void close(String reason) throws Exception;
}
```

### Descriptor Fields

`EnhancementSessionDescriptor` should include:

- `sessionId`
- `EnhancementSessionKind kind`
- `clientId`
- `clientSessionId` if available
- `commandName`
- `classPattern`
- `methodPattern`
- selected target class names
- selected loader ids if available
- `backgroundJobId` if known
- `createdAtMs`
- optional short details string

The descriptor must not hold strong references to target objects beyond class metadata already owned by command sessions. The close callback can hold the command session because it is the lifecycle handle that must be released.

### Kinds

`EnhancementSessionKind` values:

- `WATCH`
- `TRACE`
- `MONITOR`
- `STACK`
- `TT`
- `VMTOOL`
- `OTHER`

## Lifecycle Flows

### Command Start

Each command follows this order:

1. Resolve target classes.
2. Create queue/listener/session-local state.
3. Register listener with `SleuthSpyDispatcher`.
4. Add enhancer(s) to `SleuthClassFileTransformer`.
5. Retransform target class(es).
6. Register `EnhancementSessionRegistry` handle.
7. Register client disconnect cleanup that closes the registry handle, not a private stop method directly.
8. Enter collection loop.

If steps 1-5 fail, rollback stays command-local and no registry handle is registered.

### Command Completion

Normal command completion closes the registry handle:

1. Set command cancellation/closed flag.
2. Remove enhancer(s).
3. Retransform target class(es).
4. Unregister listener/track state.
5. Remove registry entry.
6. Remove client disconnect cleanup.

Close must be idempotent. It must be safe for normal completion and reset to race.

### Client Disconnect

`ClientSession` cleanup should close registry handles by id. Existing cleanup registration can remain, but the cleanup action should be:

```java
() -> enhancementSessionRegistry.close(sessionId, "client_disconnect")
```

For commands that can register multiple sessions per client, the registry also supports `closeByClient(clientId, reason)`. `ClientSessionRegistry.shutdown(...)` remains a safety path.

### Reset

`ResetCommand` should close explicit sessions first:

1. `jobManager.stopAll("reset")`
2. `enhancementSessionRegistry.closeAll("reset")`
3. `vmToolSessionRegistry.stopAll(...)` as a compatibility fallback while vmtool keeps its tracker registry
4. `AgentGlobalState.resetInterceptorsBestEffort()`
5. `transformer.removeAllEnhancers()`
6. Retransform classes from the enhanced class snapshot

The reset response should include registry close counts:

```text
Reset done. jobsStopped=..., enhancementSessions=..., enhancementClosed=..., enhancedClasses=..., retransformed=..., skipped=...
```

### Status

`StatusCommand` should add a dedicated section:

```text
-- Enhancement Sessions --
Active Sessions: <n>
Watch: <n>
Trace: <n>
Monitor: <n>
Stack: <n>
TT: <n>
VmTool: <n>
Other: <n>
```

This is separate from dispatcher listener counts. Dispatcher counts show listener runtime state; enhancement session counts show command-owned lifecycle handles.

### Stop and Detach

`AttachSessionContext.close()` should close registry sessions before fallback cleanup:

1. Stop command processor intake.
2. Close enhancement sessions with reason `detach`.
3. Stop jobs and close client sessions.
4. Stop vmtool registry as compatibility fallback.
5. Clear dispatcher and destroy SpyAPI.
6. Reset bootstrap attach state.
7. Remove all transformer enhancers.
8. Retransform enhanced classes.
9. Remove transformer.
10. Close services.

`StopCommand` can keep calling the shutdown hook, because the hook reaches `AttachSessionContext.close()`. The registry does not need special stop command handling beyond the close path.

## Command Integration Details

### Watch

Modify `WatchCommand`:

- Add constructor dependency `EnhancementSessionRegistry`.
- Keep `WatchSession` for command-specific target/enhancer/queue state.
- Add `AtomicBoolean cancelled` or `closed` to the session.
- Register handle after successful retransform and `activeSessions.put(...)`.
- Convert client cleanup to close the registry handle.
- Convert finally cleanup to close the handle.
- Update polling loop to exit when cancelled.
- Keep `stopWatch(watchId)` as private cleanup implementation, but have registry close call it.
- Make `stopWatch` idempotent and set cancellation state.

### Trace

Apply the same pattern as watch:

- Add registry dependency.
- Add cancellation state.
- Register handle after successful retransform.
- Route disconnect/finally through registry.
- Ensure `TraceAggregator` loop exits promptly when cancelled.

### Monitor

Modify `MonitorCommand`:

- Add registry dependency.
- Register a handle after all selected classes are enhanced and retransformed.
- Track all enhanced classes in the session descriptor.
- Add cancellation state and check it between interval sleeps.
- On close, remove each enhancer and retransform each class best-effort.
- Keep listener unregister in finally.

### Stack

Modify `StackCommand` and `StackTraceLiteEngine`:

- Pass registry into `StackTraceLiteEngine`.
- Register handle after successful stack enhancer setup.
- Add cancellation state to `StackSession`.
- Poll loop exits when cancelled.
- Client cleanup and final cleanup close the registry handle.

### TT

Modify `TtCommand` and `TtRecordEngine`:

- Pass registry into `TtRecordEngine`.
- Register handle after successful TT enhancer setup.
- Add cancellation state to `TtSession`.
- Poll loop exits when cancelled.
- Preserve `TtRecordStore` behavior and `tt list/detail/replay/clear`.
- `tt stop <ttId>` should close through registry and return not found if neither engine nor registry has the session.

### VmTool

Modify `VmToolSessionRegistry` and `VmToolCommand` carefully:

- Keep `VmToolSessionRegistry` as owner of track data and instance operations.
- Add `EnhancementSessionRegistry` dependency to `VmToolCommand`; keep `VmToolSessionRegistry` independent from the unified registry.
- When `startTrack(...)` succeeds, register a `VMTOOL` enhancement session handle.
- The close callback should call `vmToolSessionRegistry.stopTrack(...)`.
- `vmtool stop <track-id>` should close through `EnhancementSessionRegistry` first, then fall back to `VmToolSessionRegistry.stopTrack(...)` if no handle exists.
- `vmtool tracks` can continue listing vmtool track sessions, but status must use the unified registry for lifecycle counts.

## Idempotency and Concurrency Rules

- Every registered session must close at most once.
- Closing a missing session returns false and must not throw.
- `closeAll` snapshots ids before closing to avoid concurrent modification issues.
- Close callback exceptions are caught and recorded in summary, not propagated to reset/detach callers.
- Command-local cleanup methods remain best-effort.
- A command that completes naturally after reset must not re-add cleanup or fail because the session is already closed.
- `SleuthSpyDispatcher.unregister(id)` is always best-effort and safe to call even if dispatcher was already cleared.
- Transformer enhancer removal and retransformation are best-effort per class.

## Error Handling

The registry stores close failures in `EnhancementSessionCloseSummary`:

- total sessions selected
- closed successfully
- already closed/missing
- failed
- failed ids with short error messages

`reset` and detach should continue even when session close fails. The fallback transformer cleanup still runs.

## Testing Strategy

### Unit Tests for Registry

Create tests under:

- `core/src/test/java/com/javasleuth/core/enhancement/session/EnhancementSessionRegistryTest.java`

Cover:

- register/list/count
- close by id
- close all
- close by client
- idempotent double close
- close callback throws but registry removes or marks session closed
- count by kind after close

### Command Lifecycle Tests

Extend or add tests under:

- `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java`
- `core/src/test/java/com/javasleuth/command/EnhancementSessionResetTest.java`
- `core/src/test/java/com/javasleuth/command/ListenerModeRequirementTest.java`

Cover:

- watch registers a registry session while active.
- trace registers a registry session while active.
- monitor registers a registry session while active.
- stack registers a registry session while active.
- tt registers a registry session while active.
- vmtool track registers a registry session while active.
- client disconnect closes registry sessions.
- command natural completion removes registry sessions.
- `reset` closes all active registry sessions and dispatcher listener counts become zero.

### Runtime Close Tests

Extend:

- `core/src/test/java/com/javasleuth/core/agent/runtime/AttachSessionContextTest.java`

Cover:

- attach context exposes a non-null `EnhancementSessionRegistry`.
- `close()` closes registry sessions before transformer fallback.
- repeated `close()` is idempotent.

### Status Tests

Add or extend a command test to assert:

- `StatusCommand` includes `-- Enhancement Sessions --`.
- counts reflect active registry sessions by kind.
- zero state is reported clearly.

### Verification Commands

Run focused tests first:

```bash
mvn -pl core -Dtest=EnhancementSessionRegistryTest test
mvn -pl core -Dtest=SessionCleanupOnDisconnectTest test
mvn -pl core -Dtest=EnhancementSessionResetTest test
mvn -pl core -Dtest=AttachSessionContextTest test
```

Then run the core suite:

```bash
mvn -pl core test
```

If the repository requires dependent modules for compilation, run:

```bash
mvn -pl core -am test
```

## Implementation Plan 1: Registry Foundation and Composition

Files to create:

- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionRegistry.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionKind.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionDescriptor.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionHandle.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionSnapshot.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionCloseSummary.java`
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionCloser.java`
- `core/src/test/java/com/javasleuth/core/enhancement/session/EnhancementSessionRegistryTest.java`

Files to modify:

- `core/src/main/java/com/javasleuth/core/agent/runtime/AttachSessionContext.java`
- `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactoryRequest.java`
- `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java`
- `core/src/main/java/com/javasleuth/core/command/CommandProviderContext.java`
- `core/src/main/java/com/javasleuth/core/command/CommandProcessorComponents.java`

Tasks:

1. Implement registry and value types.
2. Add attach-scope registry creation in `AttachSessionContext`.
3. Expose registry getter for tests and command construction.
4. Thread registry through command factory request and provider context.
5. Add owned-resource shutdown for factory-created registries.
6. Add registry unit tests.

Acceptance:

- Registry unit tests pass.
- Existing command construction still compiles.
- Attach session test can assert registry is non-null.

## Implementation Plan 2: Watch and Trace Migration

Files to modify:

- `core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java`
- `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java`

Tasks:

1. Inject registry into watch/trace constructors.
2. Add session cancellation state.
3. Register `WATCH` and `TRACE` handles after successful setup.
4. Route client cleanup and final cleanup through registry.
5. Make polling loops exit when the session is cancelled.
6. Update tests to assert registry count while commands are active and zero after disconnect.

Acceptance:

- Watch/trace sessions appear in registry while active.
- Disconnect closes watch/trace through registry.
- Existing listener requirement tests still pass.

## Implementation Plan 3: Monitor, Stack, and TT Migration

Files to modify:

- `core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/StackCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java`
- `core/src/main/java/com/javasleuth/core/command/impl/TtCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java`
- `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java`

Tasks:

1. Inject registry into monitor, stack engine, and tt engine.
2. Add cancellation state to monitor, stack, and tt session objects.
3. Register `MONITOR`, `STACK`, and `TT` handles after successful setup.
4. Route client cleanup and final cleanup through registry.
5. Make sleep/poll loops exit promptly on cancellation.
6. Update `tt stop <ttId>` to use registry-backed close.
7. Add tests for active registry entries and cleanup.

Acceptance:

- Monitor/stack/tt sessions appear in registry while active.
- Reset or disconnect closes them promptly.
- `tt list/detail/replay/clear` behavior is unchanged.

## Implementation Plan 4: VmTool Lifecycle Integration

Files to modify:

- `core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java`
- `core/src/main/java/com/javasleuth/core/vmtool/VmToolSessionRegistry.java`
- `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- `core/src/test/java/com/javasleuth/command/ListenerModeRequirementTest.java`
- `core/src/test/java/com/javasleuth/command/EnhancementSessionResetTest.java`

Tasks:

1. Register a `VMTOOL` session handle when `vmtool track` succeeds.
2. Make the handle close call `VmToolSessionRegistry.stopTrack(...)`.
3. Change `vmtool stop <track-id>` to close through the registry first.
4. Keep vmtool track listing and instance APIs backed by `VmToolSessionRegistry`.
5. Keep `VmToolSessionRegistry.shutdown(...)` as fallback cleanup.
6. Add tests for track registration, stop, reset, and shutdown behavior.

Acceptance:

- Active vmtool tracks are counted by the unified registry.
- `vmtool stop` removes both registry handle and vmtool tracking data.
- `reset` closes vmtool via unified lifecycle and fallback remains safe.

## Implementation Plan 5: Reset, Status, Stop, and Detach

Files to modify:

- `core/src/main/java/com/javasleuth/core/command/impl/ResetCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`
- `core/src/main/java/com/javasleuth/core/agent/runtime/AttachSessionContext.java`
- `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- `core/src/test/java/com/javasleuth/command/EnhancementSessionResetTest.java`
- `core/src/test/java/com/javasleuth/core/agent/runtime/AttachSessionContextTest.java`

Tasks:

1. Inject registry into `ResetCommand` and `StatusCommand`.
2. Make `reset` close all registry sessions before transformer fallback cleanup.
3. Include registry close summary in reset output.
4. Add status section for registry counts.
5. Make attach close path close registry sessions before fallback cleanup.
6. Verify `StopCommand` reaches the updated attach close path through shutdown hook.
7. Add tests for reset, status, and attach close.

Acceptance:

- `reset` closes all active enhancement sessions.
- `status` displays registry counts by kind.
- `AttachSessionContext.close()` leaves registry empty.
- Existing fallback cleanup still runs after registry close.

## Implementation Plan 6: Documentation and Regression Sweep

Files to modify:

- `docs/tutorial/command-instrumentation-and-rollback.md`
- `docs/usage/commands.md`
- `docs/usage/troubleshooting.md`

Tasks:

1. Update tutorial text so reset/stop/session cleanup describe registry-owned enhancement sessions.
2. Update command docs for status/reset output changes.
3. Document that dispatcher counts and enhancement session counts are related but not identical.
4. Update troubleshooting guidance for reset/stop/detach cleanup failures and explain registry summary counts.
5. Run focused tests and full core test suite.
6. Review for stale direct cleanup patterns:
   - command-local cleanup is allowed only behind registry handles.
   - client cleanup must close registry handles.
   - reset/detach must close registry before transformer fallback.

Acceptance:

- Docs match new lifecycle behavior.
- No affected command maintains an active enhancement session that is invisible to the registry.
- Full core tests pass.

## Open Design Decisions

These decisions are fixed for implementation unless code evidence found during implementation forces a change:

- Registry close removes session entries even if callback fails, and records the failure in summary.
- Command-local session maps may remain as private implementation details, but every active enhancement session must have a registry handle.
- `VmToolSessionRegistry` remains because it owns instance tracking, but its enhancement lifecycle is represented in the unified registry.
- `status` shows counts only; detailed per-session output is outside this spec and should be handled by a separate approved design.

## Success Criteria

- All six instrumentation commands register active enhancement sessions in one attach-scope registry.
- `reset` closes all enhancement sessions before fallback cleanup.
- Client disconnect closes active enhancement sessions through the registry.
- `status` reports active enhancement session counts by kind.
- `stop` and detach close registry sessions through the attach close path.
- Session close is idempotent under normal completion, reset, disconnect, and detach races.
- Tests cover registry behavior, command cleanup, reset, status, and attach close.
