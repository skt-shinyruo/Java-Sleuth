# Listener/Advice Unified Event Model Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make “enhanced bytecode → single SpyAPI → core Listener/Advice dispatcher” the default runtime path, and migrate `watch/trace` to core listeners (including `invoke after/exception` events), while keeping detach safe (no isolated ClassLoader leaks).

**Architecture:** Install a core-side `SpyDispatcher` (implements `com.javasleuth.bootstrap.spy.SleuthSpyAPI.AbstractSpy`) on attach via `SleuthSpyAPI.setSpy(...)`, route all advice events to registered listeners keyed by `listenerId`, and clear references on detach via `SleuthSpyAPI.destroy()/setNopSpy()`. Gradually move business logic from bootstrap interceptors into core listeners, keeping a thin legacy bridge for un-migrated commands.

**Tech Stack:** ASM (`AdviceAdapter`), Java agent attach/detach lifecycle, concurrent registries (`ConcurrentHashMap`), queues (`BlockingQueue`), existing bootstrap data models (`WatchResult`, `TraceResult`, `TraceAggregator`).

---

### Task 1: Add core SpyDispatcher + listener API

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/spy/SleuthAdviceListener.java`
- Create: `core/src/main/java/com/javasleuth/core/spy/SleuthSpyDispatcher.java`
- Create: `core/src/main/java/com/javasleuth/core/spy/listener/WatchAdviceListener.java`
- Create: `core/src/main/java/com/javasleuth/core/spy/listener/TraceAdviceListener.java`

**Steps:**
1. Define a minimal listener interface that mirrors `SleuthSpyAPI` callbacks (no extra allocations).
2. Implement a dispatcher that:
   - `register(id, listener)` / `unregister(id)` / `clear()`
   - Swallows listener exceptions (never break business thread)
   - Exposes stats for `status` / tests (counts per listener kind).
3. Implement `WatchAdviceListener` to publish `WatchResult` to a queue (drop/evict policy compatible with existing behavior).
4. Implement `TraceAdviceListener` to publish `TraceResult` + track method depth + pair invoke before/after to compute sub-call duration.

### Task 2: Install / uninstall spy on attach/detach

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentRuntime.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`

**Steps:**
1. Create one `SleuthSpyDispatcher` per runtime.
2. On runtime start: `SleuthSpyAPI.setSpy(dispatcher)` + `SleuthSpyAPI.init()` (best-effort).
3. On runtime close: `dispatcher.clear()` then `SleuthSpyAPI.destroy()` (best-effort) to drop isolated ClassLoader references.
4. Pass dispatcher into commands that need it (watch/trace/status/tests).

### Task 3: Migrate `watch` to listener

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java`
- Test: `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java`

**Steps:**
1. Replace `WatchInterceptor.registerWatch/unregisterWatch` with dispatcher registration.
2. Keep enhancer behavior unchanged (still injects SpyAPI calls).
3. Ensure session cleanup unregisters the listener even if enhancement/retransform fails.

### Task 4: Migrate `trace` to listener

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`
- Test: `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java`

**Steps:**
1. Replace `TraceInterceptor.registerTrace/unregisterTrace` with dispatcher registration.
2. Keep `TraceAggregator` as-is; feed it `TraceResult` from listener.
3. Expose active trace count via dispatcher for status/test.

### Task 5: Implement invoke after/exception injection in TraceEnhancer

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/enhancement/TraceEnhancer.java`
- Test: `core/src/test/java/com/javasleuth/enhancement/TraceEnhancerInvokeEventsTest.java`

**Steps:**
1. For each traced invoke, inject:
   - `atBeforeInvoke` before the invoke instruction
   - `atAfterInvoke` on normal completion (preserve return stack)
   - `atInvokeException` in a per-invoke catch handler (rethrow)
2. Add a unit test that asserts transformed bytecode contains `atAfterInvoke/atInvokeException` for non-skipped invocations.

### Task 6: Verification

**Commands:**
- Run: `mvn clean test`

**Expected:**
- All modules build; tests pass; no new leaks/regressions in runtime lifecycle tests.

