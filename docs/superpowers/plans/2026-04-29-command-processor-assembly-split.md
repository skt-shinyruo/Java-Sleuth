# Command Processor Assembly Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `CommandProcessorFactory` assembly into focused runtime, command, server, and resource-closing factories while preserving existing lifecycle behavior.

**Architecture:** Keep `CommandProcessorFactory` as the public facade and move concrete assembly into package-private helpers in `com.javasleuth.core.command`. `RuntimeServicesFactory` resolves defaults and ownership, `CommandSubsystemFactory` wires registry/pipeline, `ServerSubsystemFactory` wires server state/executor/lifecycle objects, and `ResourceCloser` builds the owned closeable.

**Tech Stack:** Java 8, JUnit 4, Maven reactor modules `bootstrap`, `foundation`, and `core`.

---

## File Structure

- Create `core/src/main/java/com/javasleuth/core/command/ResourceOwnership.java`
  - Package-private immutable ownership flags for default-created resources.
- Create `core/src/main/java/com/javasleuth/core/command/RuntimeServices.java`
  - Package-private immutable value object containing resolved runtime dependencies and typed config.
- Create `core/src/main/java/com/javasleuth/core/command/RuntimeServicesFactory.java`
  - Resolves `CommandProcessorFactoryRequest` into `RuntimeServices`.
- Create `core/src/main/java/com/javasleuth/core/command/CommandSubsystem.java`
  - Package-private value object for registry and pipeline.
- Create `core/src/main/java/com/javasleuth/core/command/CommandSubsystemFactory.java`
  - Builds provider context, provider loader, registry, and pipeline.
- Create `core/src/main/java/com/javasleuth/core/command/ServerSubsystem.java`
  - Package-private value object for server lifecycle components.
- Create `core/src/main/java/com/javasleuth/core/command/ServerSubsystemFactory.java`
  - Builds executor, session index, server bootstrapper, handler, acceptor, and shutdown coordinator.
- Create `core/src/main/java/com/javasleuth/core/command/ResourceCloser.java`
  - Builds owned resource closeables from `ResourceOwnership`.
- Modify `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java`
  - Delegate canonical assembly to the new factories.
- Modify `core/src/main/java/com/javasleuth/core/command/CommandProcessorComponents.java`
  - Add package-private `from(...)` factory for readable final assembly.
- Modify `core/src/main/java/com/javasleuth/core/command/CommandProcessor.java`
  - Deprecate long positional constructor only.
- Add tests:
  - `core/src/test/java/com/javasleuth/command/ResourceCloserTest.java`
  - `core/src/test/java/com/javasleuth/command/RuntimeServicesFactoryTest.java`
  - `core/src/test/java/com/javasleuth/command/CommandSubsystemFactoryTest.java`
  - `core/src/test/java/com/javasleuth/command/ServerSubsystemFactoryTest.java`

## Task 1: Resource Ownership and ResourceCloser

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/command/ResourceOwnership.java`
- Create: `core/src/main/java/com/javasleuth/core/command/ResourceCloser.java`
- Test: `core/src/test/java/com/javasleuth/command/ResourceCloserTest.java`

- [ ] **Step 1: Write failing resource closer tests**

```java
@Test
public void fromOwnedResources_closesOwnedResourcesInLifecycleOrder() throws Exception {
    List<String> closed = new ArrayList<>();
    ResourceOwnership ownership = new ResourceOwnership(true, true, true, true, true, true, true, true);

    AutoCloseable closer = ResourceCloser.fromOwnedResources(
        ownership,
        recording(closed, "audit"),
        recording(closed, "authn"),
        recording(closed, "dangerous"),
        recording(closed, "perf"),
        recording(closed, "vmtool"),
        recording(closed, "client"),
        recording(closed, "job"),
        recording(closed, "enhancement")
    );

    closer.close();

    Assert.assertEquals(Arrays.asList("enhancement", "job", "client", "vmtool", "perf", "dangerous", "authn", "audit"), closed);
}

@Test
public void fromOwnedResources_skipsInjectedResources() throws Exception {
    List<String> closed = new ArrayList<>();
    ResourceOwnership ownership = new ResourceOwnership(false, false, false, false, true, false, false, true);

    AutoCloseable closer = ResourceCloser.fromOwnedResources(
        ownership,
        recording(closed, "audit"),
        recording(closed, "authn"),
        recording(closed, "dangerous"),
        recording(closed, "perf"),
        recording(closed, "vmtool"),
        recording(closed, "client"),
        recording(closed, "job"),
        recording(closed, "enhancement")
    );

    closer.close();

    Assert.assertEquals(Arrays.asList("enhancement", "job"), closed);
}
```

- [ ] **Step 2: Run red test**

Run:

```bash
mvn -pl core -am -Dtest=ResourceCloserTest test
```

Expected: test compilation fails because `ResourceOwnership` and `ResourceCloser` do not exist.

- [ ] **Step 3: Implement resource ownership and closer**

Implement immutable booleans, `hasOwnedResources()`, and `ResourceCloser.fromOwnedResources(...)`.
`ResourceCloser` should add closeables in dependency-first order and use `CommandProcessorOwnedResources`
so the observable close order remains enhancement, job, client session, vmtool, performance,
dangerous confirm, authentication, audit.

- [ ] **Step 4: Run green test**

Run:

```bash
mvn -pl core -am -Dtest=ResourceCloserTest test
```

Expected: `ResourceCloserTest` passes.

## Task 2: RuntimeServicesFactory

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/command/RuntimeServices.java`
- Create: `core/src/main/java/com/javasleuth/core/command/RuntimeServicesFactory.java`
- Test: `core/src/test/java/com/javasleuth/command/RuntimeServicesFactoryTest.java`

- [ ] **Step 1: Write failing runtime services tests**

```java
@Test
public void create_usesDefaultsAndMarksCreatedResourcesOwned() {
    RuntimeServices services = RuntimeServicesFactory.create(
        CommandProcessorFactoryRequest.builder(fakeInstrumentation(), new SleuthClassFileTransformer(ProductionConfig.createDefault())).build()
    );
    try {
        Assert.assertNotNull(services.config);
        Assert.assertNotNull(services.auditLogger);
        Assert.assertNotNull(services.authenticationManager);
        Assert.assertNotNull(services.metricsCollector);
        Assert.assertTrue(services.ownership.ownsAuditLogger());
        Assert.assertTrue(services.ownership.ownsAuthenticationManager());
        Assert.assertTrue(services.ownership.ownsClientSessionRegistry());
        Assert.assertTrue(services.ownership.ownsEnhancementSessionRegistry());
    } finally {
        closeQuietly(ResourceCloser.forOwnedResources(services));
    }
}

@Test
public void create_reusesInjectedServicesAndDoesNotOwnThem() {
    ProductionConfig config = ProductionConfig.createDefault();
    AuditLogger audit = new AuditLogger(config);
    AuthenticationManager authn = new AuthenticationManager(config, audit);
    DangerousCommandConfirmationManager dangerous = new DangerousCommandConfirmationManager(config, audit);
    ClientSessionRegistry clientSessions = new ClientSessionRegistry();
    MetricsCollector metrics = new MetricsCollector(config);
    JobManager jobs = new JobManager();
    VmToolSessionRegistry vmtool = new VmToolSessionRegistry();
    PerformanceOptimizer perf = new PerformanceOptimizer(config);
    EnhancementSessionRegistry enhancements = new EnhancementSessionRegistry();

    RuntimeServices services = RuntimeServicesFactory.create(
        CommandProcessorFactoryRequest.builder(fakeInstrumentation(), new SleuthClassFileTransformer(config))
            .withConfig(config)
            .withAuditLogger(audit)
            .withAuthenticationManager(authn)
            .withDangerousConfirm(dangerous)
            .withClientSessionRegistry(clientSessions)
            .withMetricsCollector(metrics)
            .withJobManager(jobs)
            .withVmToolSessionRegistry(vmtool)
            .withPerformanceOptimizer(perf)
            .withEnhancementSessionRegistry(enhancements)
            .build()
    );

    Assert.assertSame(audit, services.auditLogger);
    Assert.assertSame(authn, services.authenticationManager);
    Assert.assertSame(clientSessions, services.clientSessionRegistry);
    Assert.assertFalse(services.ownership.hasOwnedResources());
}
```

- [ ] **Step 2: Run red test**

Run:

```bash
mvn -pl core -am -Dtest=RuntimeServicesFactoryTest test
```

Expected: test compilation fails because `RuntimeServices` and `RuntimeServicesFactory` do not exist.

- [ ] **Step 3: Implement RuntimeServices and RuntimeServicesFactory**

Move validation, default service creation, typed config parsing, `InputValidator` creation, and initial
audit dropped metric recording from `CommandProcessorFactory` into the new factory.

- [ ] **Step 4: Run green test**

Run:

```bash
mvn -pl core -am -Dtest=RuntimeServicesFactoryTest test
```

Expected: `RuntimeServicesFactoryTest` passes.

## Task 3: CommandSubsystemFactory

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/command/CommandSubsystem.java`
- Create: `core/src/main/java/com/javasleuth/core/command/CommandSubsystemFactory.java`
- Test: `core/src/test/java/com/javasleuth/command/CommandSubsystemFactoryTest.java`

- [ ] **Step 1: Write failing command subsystem test**

```java
@Test
public void create_buildsRegistryWithBuiltinHelpAndPipeline() {
    RuntimeServices services = RuntimeServicesFactory.create(defaultRequest());
    try {
        CommandSubsystem subsystem = CommandSubsystemFactory.create(services);

        Assert.assertNotNull(subsystem.registry);
        Assert.assertNotNull(subsystem.pipeline);
        Assert.assertTrue(subsystem.registry.getCommandMap().containsKey("help"));
    } finally {
        closeQuietly(ResourceCloser.forOwnedResources(services));
    }
}
```

- [ ] **Step 2: Run red test**

Run:

```bash
mvn -pl core -am -Dtest=CommandSubsystemFactoryTest test
```

Expected: test compilation fails because `CommandSubsystem` and `CommandSubsystemFactory` do not exist.

- [ ] **Step 3: Implement CommandSubsystem and CommandSubsystemFactory**

Move provider context construction, built-in provider creation, provider loading, registry creation,
and pipeline creation from `CommandProcessorFactory` into the command subsystem factory.

- [ ] **Step 4: Run green test**

Run:

```bash
mvn -pl core -am -Dtest=CommandSubsystemFactoryTest test
```

Expected: `CommandSubsystemFactoryTest` passes.

## Task 4: ServerSubsystemFactory

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/command/ServerSubsystem.java`
- Create: `core/src/main/java/com/javasleuth/core/command/ServerSubsystemFactory.java`
- Test: `core/src/test/java/com/javasleuth/command/ServerSubsystemFactoryTest.java`

- [ ] **Step 1: Write failing server subsystem test**

```java
@Test
public void create_usesConfiguredBoundedExecutorAndCallerRunsPolicy() {
    String oldCap = System.getProperty("sleuth.server.executor.queue.capacity");
    try {
        System.setProperty("sleuth.server.executor.queue.capacity", "2");
        RuntimeServices services = RuntimeServicesFactory.create(defaultRequest());
        CommandSubsystem command = CommandSubsystemFactory.create(services);
        ServerSubsystem server = ServerSubsystemFactory.create(services, command);

        Assert.assertTrue(server.clientExecutor.getQueue() instanceof LinkedBlockingQueue);
        Assert.assertEquals(2, server.clientExecutor.getQueue().remainingCapacity());
        Assert.assertTrue(server.clientExecutor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy);
        Assert.assertNotNull(server.clientHandler);
        Assert.assertNotNull(server.bootstrapper);
        Assert.assertNotNull(server.acceptor);
        Assert.assertNotNull(server.shutdownCoordinator);
    } finally {
        restoreProperty("sleuth.server.executor.queue.capacity", oldCap);
    }
}
```

- [ ] **Step 2: Run red test**

Run:

```bash
mvn -pl core -am -Dtest=ServerSubsystemFactoryTest test
```

Expected: test compilation fails because `ServerSubsystem` and `ServerSubsystemFactory` do not exist.

- [ ] **Step 3: Implement ServerSubsystem and ServerSubsystemFactory**

Move running state, command counter, thread pool creation, server bootstrapper configuration, session
index, client handler, connection acceptor, and shutdown coordinator creation from
`CommandProcessorFactory` into the server subsystem factory.

- [ ] **Step 4: Run green test**

Run:

```bash
mvn -pl core -am -Dtest=ServerSubsystemFactoryTest test
```

Expected: `ServerSubsystemFactoryTest` passes.

## Task 5: Delegate CommandProcessorFactory

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProcessorComponents.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProcessor.java`
- Test: existing command processor tests plus new factory tests

- [ ] **Step 1: Write failing integration assertion**

Add or update tests to verify `CommandProcessorFactory.createComponents(request)` still returns populated
components and injected metrics are preserved:

```java
CommandProcessorComponents components = CommandProcessorFactory.createComponents(request);
Assert.assertSame(request.getMetricsCollector(), components.getMetricsCollector());
Assert.assertNotNull(components.getRegistry());
Assert.assertNotNull(components.getPipeline());
Assert.assertNotNull(components.getClientHandler());
Assert.assertNotNull(components.getShutdownCoordinator());
```

- [ ] **Step 2: Run red test**

Run:

```bash
mvn -pl core -am -Dtest=CommandProcessorFactoryRequestTest test
```

Expected before delegation may pass. If it passes, rely on the new failing unit tests from Tasks 1-4
as the red stage and keep this as regression coverage.

- [ ] **Step 3: Replace large factory body with delegation**

Canonical flow:

```java
RuntimeServices services = RuntimeServicesFactory.create(request);
CommandSubsystem commandSubsystem = CommandSubsystemFactory.create(services);
ServerSubsystem serverSubsystem = ServerSubsystemFactory.create(services, commandSubsystem);
AutoCloseable ownedResources = ResourceCloser.forOwnedResources(services);
logInitializedBestEffort(services.auditLogger, commandSubsystem.registry);
return CommandProcessorComponents.from(services, commandSubsystem, serverSubsystem, ownedResources);
```

Update positional overloads to build a request and call the canonical method. Mark long positional
overloads deprecated.

- [ ] **Step 4: Run targeted regression suite**

Run:

```bash
mvn -pl core -am -Dtest=ResourceCloserTest,RuntimeServicesFactoryTest,CommandSubsystemFactoryTest,ServerSubsystemFactoryTest,CommandProcessorFactoryRequestTest,CommandProcessorOwnedResourcesCloseTest,CommandProcessorExecutorQueueTest,CommandProcessorMaxConnectionsTest,CommandProcessorShutdownHookTest test
```

Expected: all listed tests pass.

## Task 6: Final Verification

**Files:**
- All changed implementation, test, and plan files.

- [ ] **Step 1: Run broader core suite**

Run:

```bash
mvn -pl core -am test
```

Expected: reactor modules through `core` build, and core tests pass.

- [ ] **Step 2: Inspect diff**

Run:

```bash
git status --short
git diff --stat
git diff -- core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java
```

Expected: factory body is short delegation logic; no unrelated files are changed.

- [ ] **Step 3: Commit implementation**

Run:

```bash
git add docs/superpowers/plans/2026-04-29-command-processor-assembly-split.md core/src/main/java/com/javasleuth/core/command core/src/test/java/com/javasleuth/command
git commit -m "refactor: split command processor assembly"
```
