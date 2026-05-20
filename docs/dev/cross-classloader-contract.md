# Cross-ClassLoader Contract (SSOT)

Java-Sleuth uses a two-stage agent design similar to Arthas:

- `agent` (bootstrap agent, JDK-only): runs in the target JVM’s system classloader, appends the bootstrap bridge to `BootstrapClassLoader`, then creates an isolated `URLClassLoader` to load the runtime container.
- `bootstrap` (bootstrap bridge, JDK-only): classes that must be visible to enhanced bytecode and to the thin agent via reflection.
- `container` + `core`: runtime implementation loaded by the isolated classloader.

Because of the classloader boundaries, some integration points are intentionally bound by **strings + reflection**.
These are **stable contracts** and must not be renamed casually.

Implementation rule:

- Thin-agent side reflection for these contracts is centralized in `agent/src/main/java/com/javasleuth/agent/CrossClassLoaderFacade.java`.
- New thin-agent startup logic must go through that facade instead of scattering new `Class.forName(...)` / `getMethod(...)` calls in `SleuthAgent`.
- Stable class names, method names, parameter types, return types, and accepted contract versions are declared as contract metadata in `CrossClassLoaderFacade`.
- A `contractVersion()` match alone is not sufficient. Startup must first verify every stable class and method signature, then verify the version range.
- Failure messages must name the contract (`bootstrap bridge` or `isolated container`), status (`MISSING_CLASS`, `MISSING_METHOD`, `BAD_RETURN_TYPE`, `INCOMPATIBLE_VERSION`, `INVOCATION_FAILED`), and the expected class or method signature.

## Contract Items

### Bootstrap-visible classes (must remain JDK-only)

Used by the thin agent and/or injected bytecode:

- `com.javasleuth.bootstrap.agent.AgentLifecycle`
  - `contractVersion() : int`
  - `tryBeginAttach() : long`
  - `applyAgentArgsIfAbsent(long, String) : boolean`
  - `commitIsolatedClassLoader(long, ClassLoader) : boolean`
  - `failBestEffort(long, ClassLoader) : void`
  - `detachBestEffort(ClassLoader) : void`
- `com.javasleuth.bootstrap.util.JarLocator`
  - `locateAgentJar(Class) : File`
  - `locateAgentContainerJar(Class) : File`
- `com.javasleuth.bootstrap.spy.SleuthSpyAPI` (bootstrap-visible callback used by enhanced bytecode)
  - `atEnter(String, Class, String, Object, Object[], long) : void`
  - `atExit(String, Class, String, Object, Object[], Object, boolean, long, long) : void`
  - `atExceptionExit(String, Class, String, Object, Object[], Throwable, long, long) : void`
  - `atBeforeInvoke(String, Class, String, Object, long) : void`
  - `atAfterInvoke(String, Class, String, Object, long) : void`
  - `atInvokeException(String, Class, String, Object, Throwable, long) : void`
  - `onConstructed(String, Object) : void`

### Isolated container entrypoint

Loaded by the isolated classloader and invoked by the thin agent:

- `com.javasleuth.container.SleuthAgentContainerEntrypoint`
  - `contractVersion() : int`
  - `agentmain(String, Instrumentation) : void`
  - `premain(String, Instrumentation) : void`

## Versioning

The bootstrap bridge and isolated container each expose an ABI contract version:

- bootstrap bridge: `AgentLifecycle.contractVersion() : int`
- isolated container: `SleuthAgentContainerEntrypoint.contractVersion() : int`

Both start at `1`. Increment the relevant contract version when a stable cross-ClassLoader class, method signature, or required startup semantic becomes incompatible. Do not use the Maven artifact version as the contract version.

Version checks are range-based in the thin agent (`MIN_*_CONTRACT_VERSION` / `MAX_*_CONTRACT_VERSION`). A version is accepted only after all stable classes and methods for that contract have been verified.

## Startup Validation Flow

1. The thin agent appends the bootstrap bridge jar to the bootstrap search path.
2. `CrossClassLoaderFacade.isBootstrapBridgeAvailable()` checks that required bootstrap-visible classes are actually loaded by `BootstrapClassLoader`.
3. `CrossClassLoaderFacade.verifyBootstrapContract()` verifies `AgentLifecycle`, `JarLocator`, and `SleuthSpyAPI` signatures, then verifies the bootstrap version range.
4. The thin agent creates the isolated container classloader.
5. `CrossClassLoaderFacade.verifyContainerEntrypointContract()` verifies `SleuthAgentContainerEntrypoint` signatures and container version range before invoking `agentmain` or `premain`.
6. Startup aborts with a diagnostic message if any contract check fails. Raw `ClassNotFoundException` / `NoSuchMethodException` should not be the primary operator-facing error.

## Verification

Unit tests validate the reflection contract so refactors fail fast in CI:

- `agent/src/test/java/com/javasleuth/agent/CrossClassLoaderFacadeContractTest.java`
- `core/src/test/java/com/javasleuth/bootstrap/agent/AgentLifecycleTest.java`
- `core/src/test/java/com/javasleuth/agent/CrossClassLoaderReflectionContractTest.java`
- `container/src/test/java/com/javasleuth/container/ContainerEntrypointContractTest.java`
