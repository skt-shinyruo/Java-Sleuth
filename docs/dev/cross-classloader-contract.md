# Cross-ClassLoader Contract (SSOT)

Java-Sleuth uses a two-stage agent design similar to Arthas:

- `agent` (bootstrap agent, JDK-only): runs in the target JVM’s system classloader, appends the bootstrap bridge to `BootstrapClassLoader`, then creates an isolated `URLClassLoader` to load the runtime container.
- `bootstrap` (bootstrap bridge, JDK-only): classes that must be visible to enhanced bytecode and to the thin agent via reflection.
- `container` + `core`: runtime implementation loaded by the isolated classloader.

Because of the classloader boundaries, some integration points are intentionally bound by **strings + reflection**.
These are **stable contracts** and must not be renamed casually.

## Contract Items

### Bootstrap-visible classes (must remain JDK-only)

Used by the thin agent and/or injected bytecode:

- `com.javasleuth.bootstrap.agent.AgentLifecycle`
  - `tryBeginAttach() : long`
  - `applyAgentArgsIfAbsent(long, String) : boolean`
  - `commitIsolatedClassLoader(long, ClassLoader) : boolean`
  - `failBestEffort(long, ClassLoader) : void`
  - `detachBestEffort(ClassLoader) : void`
- `com.javasleuth.bootstrap.util.JarLocator`
  - `locateAgentContainerJar(Class) : File`
  - `locateAgentCoreJar(Class) : File`
- `com.javasleuth.bootstrap.monitor.TraceInterceptor` (bootstrap-visible callback used by enhanced bytecode)

### Isolated container entrypoint

Loaded by the isolated classloader and invoked by the thin agent:

- `com.javasleuth.container.SleuthAgentContainerEntrypoint`
  - `agentmain(String, Instrumentation) : void`
  - `premain(String, Instrumentation) : void`

## Verification

Unit tests validate the reflection contract so refactors fail fast in CI:

- `core/src/test/java/com/javasleuth/bootstrap/agent/AgentLifecycleTest.java`
- `core/src/test/java/com/javasleuth/agent/CrossClassLoaderReflectionContractTest.java`
- `container/src/test/java/com/javasleuth/container/ContainerEntrypointContractTest.java`

