# Attach-Scope Services & Runtime Resource Ownership Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Eliminate global singletons and make all threads/executors/JMX resources attach-scope and owned by `SleuthAgentRuntime.close()`, fixing detach → re-attach reliability (Issue #4/#9).

**Architecture:** Introduce `SleuthAgentServices` (attach-scope service container) created by `SleuthAgentRuntime`. Convert `ProductionConfig`, `AuditLogger`, `AuthenticationManager`, `DangerousCommandConfirmationManager`, `PerformanceOptimizer`, `MemoryOptimizer` to non-singleton, `AutoCloseable` services injected through composition roots. Shrink `ShutdownCoordinator` to only command-server shutdown.

**Tech Stack:** Java 8, Maven multi-module, JUnit 4, JMX (`MBeanServer`), JDK executors.

---

### Task 1: Add thread/MBean leak regression tests (baseline failing test)

**Files:**
- Create: `core/src/test/java/com/javasleuth/agent/core/SleuthAgentRuntimeLeakTest.java`

**Step 1: Write failing test**

Write tests that loop runtime create/close multiple rounds and assert:
- no remaining threads with name prefix `sleuth-`
- MBeans not registered: `com.javasleuth:type=PerformanceOptimizer`, `...:MemoryOptimizer`, `...:MetricsCollector`

**Step 2: Run test to verify it fails**

Run:
- `mvn -o -pl core -am clean test -Dtest=SleuthAgentRuntimeLeakTest`

Expected:
- FAIL because singletons still exist / leak resources

**Step 3: Commit (optional)**

Run:
- `git add core/src/test/java/com/javasleuth/agent/core/SleuthAgentRuntimeLeakTest.java`
- `git commit -m "test: add runtime leak regression test"`


### Task 2: Introduce attach-scope services container

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentServices.java`
- Modify: `core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentRuntime.java`

**Step 1: Write the failing test refinement**

Update `SleuthAgentRuntimeLeakTest` to create runtime via `SleuthAgentRuntime.create(...)` and assert it closes services.

**Step 2: Implement minimal container**

Implement `SleuthAgentServices` with:
- fields: `ProductionConfig`, `AuditLogger`, `AuthenticationManager`, `DangerousCommandConfirmationManager`, `PerformanceOptimizer`, `MemoryOptimizer`
- `close()` with strict order: stop `MemoryOptimizer` → stop `PerformanceOptimizer` → stop auth/loggers (best-effort, idempotent)

Update `SleuthAgentRuntime` to:
- build `SleuthAgentServices` early in `create()`
- pass services dependencies into `CommandProcessorFactory` instead of calling `getInstance()`
- store `services` field and close it in `close()`

**Step 3: Run the new/affected tests**

Run:
- `mvn -o -pl core -am clean test -Dtest=SleuthAgentRuntimeLeakTest`

Expected:
- still FAIL until we remove singletons + pipeline static usage


### Task 3: Make `ProductionConfig` attach-scope (remove singleton)

**Files:**
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/ProductionConfig.java`
- Modify: call sites that use `ProductionConfig.getInstance()`:
  - `core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentRuntime.java`
  - `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java`
  - `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentEntrypointSupport.java`
  - `core/src/main/java/com/javasleuth/core/command/server/ServerBootstrapper.java`
  - `launcher/src/main/java/com/javasleuth/launcher/attach/AgentAttacher.java` (if still used)

**Step 1: Update `ProductionConfig`**
- remove static `instance` and `getInstance/resetInstanceForDetach`
- keep behavior: sysprop override priority and `snapshot()/reloadConfiguration()`

**Step 2: Update constructors/call sites**
- all code must accept config via constructor parameters (no hidden fallback)
- update `SleuthAgentEntrypointSupport.shutdown(...)` to stop calling reset APIs

**Step 3: Run core tests**

Run:
- `mvn -o -pl core -am clean test`

Expected:
- tests compile and run; failures indicate remaining static usages


### Task 4: Make security services attach-scope (remove singleton)

**Files:**
- Modify: `foundation/src/main/java/com/javasleuth/foundation/security/AuditLogger.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/security/AuthenticationManager.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/security/DangerousCommandConfirmationManager.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/security/InputValidator.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/security/AuthorizationManager.java` (remove createDefault fallback if needed)
- Modify: `foundation/src/main/java/com/javasleuth/foundation/security/RequestSecurityManager.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/server/ShutdownCoordinator.java` (remove singleton shutdown calls)

**Step 1: Refactor constructors**
- `AuditLogger(ConfigView config, ...) implements AutoCloseable`
- `AuthenticationManager(ConfigView config, AuditLogger auditLogger) implements AutoCloseable`
- `DangerousCommandConfirmationManager(ConfigView config, AuditLogger auditLogger) implements AutoCloseable`
- remove static `getInstance/shutdownInstance`

**Step 2: Wire them from `SleuthAgentServices` into `CommandProcessorFactory`**

**Step 3: Run core tests**

Run:
- `mvn -o -pl core -am clean test`


### Task 5: Make optimizers attach-scope (remove singleton + JMX cleanup)

**Files:**
- Modify: `foundation/src/main/java/com/javasleuth/foundation/util/PerformanceOptimizer.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/util/MemoryOptimizer.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/pipeline/CacheInterceptor.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandPipeline.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/HeapDumpCommand.java`

**Step 1: Refactor optimizers**
- remove static instance + static entrypoints that allocate executors
- keep instance methods for caching and async execution
- ensure `close()` unregisters MBeans and terminates executors
- `MemoryOptimizer` uses injected `PerformanceOptimizer`

**Step 2: Wire `PerformanceOptimizer` instance into `CommandPipeline/CacheInterceptor`**

**Step 3: Fix HeapDumpCommand to use injected optimizer instance**

**Step 4: Run leak test**

Run:
- `mvn -o -pl core -am clean test -Dtest=SleuthAgentRuntimeLeakTest`

Expected:
- PASS for thread/MBean assertions


### Task 6: Plugin API breaking change: introduce `CommandProviderContext`

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/command/CommandProviderContext.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProvider.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandRegistry.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java`

**Step 1: Add context type**
- include all attach-scope dependencies used by builtin commands and expected by plugins

**Step 2: Update provider interface**
- `getCommands(CommandProviderContext ctx)`
- update builtin provider and registry/provider loader call sites

**Step 3: Run core tests**

Run:
- `mvn -o -pl core -am clean test`


### Task 7: Clean up test-only singleton resets (`SleuthTestState`)

**Files:**
- Modify: `core/src/test/java/com/javasleuth/test/SleuthTestState.java`
- Modify: tests that depend on removed singleton APIs

**Step 1: Remove singleton reset calls**
- keep bootstrap interceptor resets only

**Step 2: Run core tests**

Run:
- `mvn -o -pl core -am clean test`


### Task 8: Final verification

**Step 1: Full module test sweep**

Run:
- `mvn -o -pl core -am clean test`
- `mvn -o -pl agent -am clean test`
- `mvn -o -pl container -am clean test`

Expected:
- BUILD SUCCESS

**Step 2: Optional commit series**

Commit in small logical chunks after each task.

