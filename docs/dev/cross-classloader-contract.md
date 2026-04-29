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
  - `agentmain(String, Instrumentation) : void`
  - `premain(String, Instrumentation) : void`

The bootstrap bridge contract version is an ABI version. It starts at `1` and changes only when the thin-agent reflection contract or bootstrap-visible runtime contract becomes incompatible.

## Verification

Unit tests validate the reflection contract so refactors fail fast in CI:

- `core/src/test/java/com/javasleuth/bootstrap/agent/AgentLifecycleTest.java`
- `core/src/test/java/com/javasleuth/agent/CrossClassLoaderReflectionContractTest.java`
- `container/src/test/java/com/javasleuth/container/ContainerEntrypointContractTest.java`
