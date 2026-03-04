# Attach Lifecycle SSOT (AgentLifecycle) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Consolidate attach/detach state into a single bootstrap-visible lifecycle owner (`AgentLifecycle`) and remove legacy attach gate/reset chains.

**Architecture:** Add `AgentLifecycle` + `AttachSession` in the bootstrap bridge (JDK-only). Bootstrap agent acquires a session token before any sysprop side effects. Isolated runtime shutdown calls a single `detachBestEffort` hook to rollback sysprops and close the isolated ClassLoader.

**Tech Stack:** Java 8, Maven multi-module build, JUnit 4.

---

### Task 1: Add failing unit tests for lifecycle SSOT

**Files:**
- Create: `core/src/test/java/com/javasleuth/bootstrap/agent/AgentLifecycleTest.java`

**Step 1: Write failing test (reflection-based)**
- Add a test that expects `com.javasleuth.bootstrap.agent.AgentLifecycle` to exist with the required methods:
  - `tryBeginAttach() -> long`
  - `applyAgentArgsIfAbsent(long, String) -> boolean`
  - `commitIsolatedClassLoader(long, ClassLoader) -> boolean`
  - `failBestEffort(long, ClassLoader) -> void`
  - `detachBestEffort(ClassLoader) -> void`

**Step 2: Run test to verify it fails**
- Run: `mvn -pl core test -Dtest=AgentLifecycleTest`
- Expected: FAIL because `AgentLifecycle` is missing or methods not found.

---

### Task 2: Implement `AgentLifecycle` in bootstrap module

**Files:**
- Create: `bootstrap/src/main/java/com/javasleuth/bootstrap/agent/AgentLifecycle.java`

**Step 1: Minimal implementation**
- Implement a session token based SSOT:
  - `tryBeginAttach()` returns `0` if a session exists, otherwise creates session and returns id.
  - `applyAgentArgsIfAbsent(sessionId, agentArgs)` applies sysprops once (via `AgentArgsApplier.applyToSystemPropertiesWithRollback`) and stores rollback handle in the session.
  - `commitIsolatedClassLoader(sessionId, loader)` sets the session loader once.
  - `failBestEffort(sessionId, loaderOrNull)` clears session, rolls back sysprops, closes loader (best-effort).
  - `detachBestEffort(selfLoader)` only detaches if `selfLoader` matches committed loader.

**Step 2: Run tests**
- Run: `mvn -pl core test -Dtest=AgentLifecycleTest`
- Expected: PASS.

---

### Task 3: Switch bootstrap agent to `AgentLifecycle` and remove legacy fallback

**Files:**
- Modify: `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`

**Step 1: Update bootstrap bridge availability checks**
- Ensure `isBootstrapBridgeAvailableBestEffort()` requires `AgentLifecycle` class to be bootstrap-loaded.

**Step 2: Update attach flow**
- Acquire `sessionId` via reflection call to `AgentLifecycle.tryBeginAttach()` before any sysprop application.
- Apply agentArgs via `AgentLifecycle.applyAgentArgsIfAbsent(sessionId, agentArgs)` (reflection).
- Commit isolated loader via `AgentLifecycle.commitIsolatedClassLoader(sessionId, isolated)` (reflection).
- On any failure / early return after begin: call `AgentLifecycle.failBestEffort(sessionId, isolatedOrNull)` (reflection).
- Remove usage of `BootstrapAttachGate` and `CoreClassLoaderRegistry` from bootstrap agent flow.

**Step 3: Run core tests**
- Run: `mvn -pl core test`
- Expected: PASS.

---

### Task 4: Switch core/container shutdown to single detach hook

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentEntrypointSupport.java`

**Step 1: Replace multi-point reset list**
- Replace:
  - `SystemPropertyRollbackRegistry.rollbackAndClearBestEffort()`
  - `BootstrapAttachGateReset.resetBestEffort(...)`
  - `CoreClassLoaderRegistry.onCoreShutdown(selfClassLoader)`
- With:
  - `AgentLifecycle.detachBestEffort(selfClassLoader)`

**Step 2: Remove now-obsolete test stubs**
- Delete or update:
  - `core/src/test/java/com/javasleuth/agent/core/BootstrapAttachGateResetTest.java`
  - `core/src/test/java/com/javasleuth/agent/BootstrapAttachGate.java` (test stub)

**Step 3: Run tests**
- Run: `mvn -pl core test`
- Expected: PASS.

---

### Task 5: (Optional) Keep legacy facades as shims or deprecate

**Files:**
- Modify (optional): `bootstrap/src/main/java/com/javasleuth/bootstrap/agent/CoreClassLoaderRegistry.java`
- Modify (optional): `bootstrap/src/main/java/com/javasleuth/bootstrap/util/SystemPropertyRollbackRegistry.java`

**Step 1: Ensure no separate SSOT state remains**
- Option A (recommended): delegate these legacy APIs to `AgentLifecycle` so the SSOT is truly unique.
- Option B: mark deprecated and keep but ensure they are unused.

**Step 2: Run full test suite**
- Run: `mvn test`
- Expected: PASS.

