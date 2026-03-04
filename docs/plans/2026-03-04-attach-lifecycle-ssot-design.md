# Java-Sleuth Attach Lifecycle SSOT (Bootstrap AgentLifecycle) Design

**Date:** 2026-03-04  
**Related:** GitHub issue #3 (`[P0] 生命周期/Attach Gate 状态分散...`)

## Background / Problem

Java-Sleuth uses a two-stage agent:
- A thin **bootstrap agent** (`agent` module, JDK-only) that appends the **bootstrap bridge** (`bootstrap` module) and loads the real runtime via an **isolated ClassLoader**.
- The real implementation runs inside the isolated ClassLoader (`container` + `core`).

Attach/detach/re-attach correctness requires a clear lifecycle boundary and a single source of truth (SSOT) for "attached state".

Before this change, "already attached" and lifecycle cleanup were scattered across multiple gates:
- Bootstrap CAS gate (`com.javasleuth.agent.BootstrapAttachGate`)
- Bootstrap-visible ClassLoader registry (`com.javasleuth.bootstrap.agent.CoreClassLoaderRegistry`)
- In-isolated-loader entrypoint gates (`ATTACHED` in core/container)
- Sysprop rollback registry (`com.javasleuth.bootstrap.util.SystemPropertyRollbackRegistry`)

This creates:
- High maintenance cost: every new failure path must reset multiple gates.
- Correctness risk: side effects can happen before the authoritative gate is acquired (notably sysprop drift under concurrent attach).

## Goals (Definition of Done)

1) **Single lifecycle SSOT** (bootstrap-visible, cross-ClassLoader):
   - Exactly one authoritative attach lifecycle owner within the same JVM.
2) **No global side effects before gate acquisition**:
   - Sysprop apply (agentArgs), registry, etc. only happen after SSOT gate is successfully acquired.
3) **Detach/shutdown supports reliable re-attach**:
   - Detach clears SSOT + rolls back sysprops + closes isolated loader (best-effort).
4) **Cleanup is centralized**:
   - Avoid “N places to reset” and remove best-effort reflection scanning resets.

## Proposed Architecture

Introduce a new bootstrap-visible lifecycle owner:

`com.javasleuth.bootstrap.agent.AgentLifecycle`

It owns an `AttachSession` which becomes the only SSOT of the attach lifecycle:
- Gate (attached / attaching)
- Sysprop rollback handle (created by applying agentArgs)
- Isolated ClassLoader reference (lifecycle boundary, close on shutdown)

### Session Token

`AgentLifecycle.tryBeginAttach()` returns a `long sessionId`:
- `0` means "already attached" (either attaching or running).
- A non-zero `sessionId` must be used for all subsequent bootstrap operations:
  - `applyAgentArgsIfAbsent(sessionId, agentArgs)`
  - `commitIsolatedClassLoader(sessionId, isolatedClassLoader)`
  - `failBestEffort(sessionId, isolatedClassLoaderOrNull)`

This avoids the old concurrency window where a caller could apply global side effects without owning the attach gate.

### Detach / Shutdown

Isolated runtime shutdown calls:

`AgentLifecycle.detachBestEffort(selfClassLoader)`

This:
- clears the global session reference first (unblock re-attach even if close fails),
- rolls back sysprops recorded for the session,
- closes the isolated ClassLoader (best-effort),
- releases SSOT gate.

## Integration Points

### Bootstrap agent (`SleuthAgent`)

Bootstrap agent becomes:
1) append bridge jar (existing)
2) verify bridge availability (existing, but now includes `AgentLifecycle`)
3) begin attach session (NEW)
4) apply agentArgs sysprops (NEW; only after begin)
5) locate container/core jar (existing)
6) create isolated loader (existing)
7) commit isolated loader into lifecycle (NEW)
8) invoke container entrypoint (existing)
9) on any failure: `AgentLifecycle.failBestEffort(sessionId, isolatedOrNull)` (NEW)

### core/container shutdown

Replace multi-point “reset list” with:
- `AgentLifecycle.detachBestEffort(selfClassLoader)`

Core/container still keep their own local `ATTACHED` gate for in-loader idempotence.

## Migration / Cleanup

Immediate:
- Remove `BootstrapAttachGate` fallback usage in bootstrap agent.
- Remove `BootstrapAttachGateReset` usage in core shutdown.
- Remove `CoreClassLoaderRegistry` usage in new paths (optionally keep as deprecated shim).
- Remove `SystemPropertyRollbackRegistry` usage in new paths (optionally keep as deprecated shim).

Future (optional):
- Deprecate/remove legacy classes entirely after a release cycle.

## Testing Strategy

Add unit tests (JUnit) for `AgentLifecycle` behavior:
- `tryBeginAttach` is exclusive (second begin returns 0).
- `applyAgentArgsIfAbsent` requires session token; does not apply when token mismatch.
- `failBestEffort` rolls back sysprops and clears gate.
- `detachBestEffort` only detaches when loader matches committed loader; closes loader and rolls back sysprops.
- Re-attach works after detach/fail.

## Notes

This design intentionally mirrors Arthas’s “one SSOT + destroy() cleanup” approach:
- A single bootstrap-visible lifecycle owner
- A single cleanup hook that clears global references + releases classloader resources

