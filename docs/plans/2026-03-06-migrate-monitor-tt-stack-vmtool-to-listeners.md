# Migrate Monitor/TT/Stack/VmTool to Core Listeners Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reduce bootstrap-side static registries and make `monitor/tt/stack/vmtool` follow the same “SpyAPI → core dispatcher → listener” model as `watch/trace`, while keeping legacy fallback paths working when the dispatcher is not installed.

**Architecture:** For each command, register a core-side `SleuthAdviceListener` with `SleuthSpyDispatcher.register(listenerId, kind, listener)` when `SleuthSpyAPI` is inited and the installed spy is the same dispatcher instance. Enhanced bytecode continues calling `com.javasleuth.bootstrap.spy.SleuthSpyAPI`. On stop/session cleanup/detach, unregister listeners and clear dispatcher to avoid holding isolated ClassLoader references.

**Tech Stack:** ASM `AdviceAdapter` (already injects `SleuthSpyAPI` calls), concurrent registries (`ConcurrentHashMap`), bounded queues (`LinkedBlockingQueue`), weak refs (`WeakReference`), existing data models (`StackTraceResult`, `TtRecord`).

---

### Task 1: Expand `SleuthSpyDispatcher` listener kinds + status visibility

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/spy/SleuthSpyDispatcher.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`
- Test: `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java`

**Step 1: Add new listener kinds**
- Add `MONITOR`, `STACK`, `TT`, `VMTOOL` to `ListenerKind`
- Track counts per kind for status/tests

**Step 2: Update status output**
- Prefer dispatcher counts when installed (keep legacy bootstrap counters as fallback/compat)

**Step 3: Update session cleanup test**
- Change TT expectations from `TtInterceptor.getActiveTtCount()` to dispatcher TT count

### Task 2: Migrate `stack` trace mode to listener

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/spy/listener/StackAdviceListener.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/StackCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`

**Step 1: Implement `StackAdviceListener`**
- Build `StackTraceResult` on `onEnter`
- Apply drop/evict policy (consistent with `watch`)
- Trim stack frames (avoid `com.javasleuth.*` noise, cap depth <= 200)

**Step 2: Register via dispatcher**
- When dispatcher installed: `dispatcher.register(stackId, STACK, new StackAdviceListener(...))`
- Else fallback: `StackInterceptor.register(stackId, q, depth)`

### Task 3: Migrate `tt` to listener + core record store

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordStore.java`
- Create: `core/src/main/java/com/javasleuth/core/spy/listener/TtAdviceListener.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/TtCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`

**Step 1: Add core `TtRecordStore`**
- Maintain `recordSeq`, bounded ring buffer of last N `TtRecord`, and `clear/list/find`

**Step 2: Implement `TtAdviceListener`**
- On `onExit` / `onExceptionExit`, snapshot params/return/exception using `SleuthValueSnapshotter`
- Add to store and offer to per-session queue

**Step 3: Wire TT command to store**
- When dispatcher installed: use store for `list/detail/replay/clear`
- Else fallback: use legacy `TtInterceptor`

### Task 4: Migrate `monitor` to listener + core stats collector

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/spy/listener/MonitorAdviceListener.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`

**Step 1: Implement `MonitorAdviceListener`**
- Update per-method stats on `onExit` and `onExceptionExit`
- Expose `snapshot()` + `clear()` for the command loop

**Step 2: Register via dispatcher**
- When dispatcher installed: register listener; use listener snapshot/clear
- Else fallback to `MonitorInterceptor`

### Task 5: Migrate `vmtool track` instance capture to listener + core tracker store

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/vmtool/VmToolTracker.java`
- Create: `core/src/main/java/com/javasleuth/core/spy/listener/VmToolTrackAdviceListener.java`
- Modify: `core/src/main/java/com/javasleuth/core/vmtool/VmToolSessionRegistry.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentRuntime.java`

**Step 1: Add tracker store in core**
- Store weak refs (bounded per trackId) + stats + list/get instance

**Step 2: Register per-track listener**
- On `startTrack`: register tracker session + dispatcher listener (when installed)
- On `stopTrack/stopAll/shutdown`: unregister listener + clear tracker state
- Else fallback to legacy `VmToolInterceptor`

### Task 6: Verification

**Commands:**
- Run: `mvn clean test`

**Expected:**
- Tests pass
- `SessionCleanupOnDisconnectTest` uses dispatcher for TT
- No new cross-ClassLoader references (detach safe)

