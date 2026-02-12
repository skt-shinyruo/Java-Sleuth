# 变更提案：Agent 依赖隔离（两段式 Agent + 隔离 ClassLoader）

## Requirement Background

当前 Java-Sleuth 的 Agent 采用 `maven-assembly-plugin` 生成 `jar-with-dependencies`（fat-jar），并以 `Attach API / -javaagent` 注入到目标 JVM。

对 Java Agent 来说，如果把 ASM/Jackson/CFR/RE2J 等三方依赖以“原包名”打进注入 jar，容易产生两类风险：

1. **依赖碰撞 / 版本不兼容**：目标应用自身已经携带相同依赖时，Agent 在运行期解析到目标版本（或目标解析到 Agent 版本）都会导致不可预期行为（NoSuchMethodError、LinkageError、ClassCastException 等）。
2. **类路径污染面过大**：fat-jar 里包含的三方库会进入目标进程的类可见性范围，放大冲突概率，也增加排障复杂度。

本次变更目标：把 ASM/Jackson/CFR 等从目标 JVM 的“依赖面”彻底隔离，尽可能做到 **Agent 的依赖与目标应用依赖互不影响**。

## Change Content

1. **引入两段式 Agent（Bootstrap + Core）**
   - `java-sleuth-agent`：作为注入入口（`Agent-Class/Premain-Class`），只包含必要的引导逻辑与少量“可被业务字节码调用”的 spy/bridge 类。
   - `java-sleuth-agent-core`：承载完整实现与三方依赖（ASM/Jackson/CFR/RE2J…），由 Bootstrap 在目标 JVM 内通过隔离 ClassLoader 加载。
2. **隔离 ClassLoader 加载 Core**
   - Bootstrap 使用 `new URLClassLoader(urls, null)`（parent=bootstrap）加载 core fat-jar，避免与业务 `System/AppClassLoader` 上的同名依赖互相解析。
3. **把被插桩字节码引用的拦截器下沉到“可全局可见”的层**
   - 将 `com.javasleuth.monitor.*Interceptor`（watch/trace/monitor/tt/stack/vmtool）及其必要依赖放到 Bootstrap 可控的 classpath（优先使用 `appendToBootstrapClassLoaderSearch`），让业务类在任意 ClassLoader 下都能稳定解析到这些拦截器。
4. **Launcher 侧显式传入 Core jar 路径**
   - Launcher 在 `Attach` 时把 `coreJar=<path>` 写入 agentArgs，避免目标 JVM 端难以定位 core 包。
5. **运维/脚本/文档同步**
   - 发布包需要同时包含 `java-sleuth-agent-*-jar-with-dependencies.jar` 与 `java-sleuth-agent-core-*-jar-with-dependencies.jar`（与 launcher jar 同目录），并在文档中明确约束与回退策略。

## Impact Scope

- **Modules:** `foundation` / `core` / `launcher` /（新增）`agent`
- **Files:** `pom.xml` / `foundation/pom.xml` / `core/pom.xml` / `launcher/src/main/java/...` / `foundation/src/main/java/...` / `core/src/main/java/...` / `sleuth.sh` / `sleuth.bat` / `docs/*` / `docker/*`
- **APIs:** 对外 CLI/交互协议不变；部署产物与启动参数新增 `sleuth.agent.core.jar`（或 agentArgs `coreJar`）
- **Data:** 无

## Core Scenarios

### Requirement: 彻底隔离 Agent 三方依赖（ASM/Jackson/CFR 等）
**Module:** `agent` / `core` / `foundation` / `launcher`

#### Scenario: Attach 注入不污染目标依赖
前置条件：
- Launcher 能同时定位到 bootstrap agent jar 与 core jar
- 目标 JVM 可被 Attach

期望：
- Bootstrap 成功注入并加载 core
- Core 使用隔离 ClassLoader 自带的 ASM/Jackson/CFR，不解析到业务依赖版本
- 业务字节码插桩只引用 `com.javasleuth.monitor.*`（spy/bridge），不引用 ASM/Jackson/CFR
- Watch/Trace/Monitor/Tt/Stack/VmTool 等功能正常

#### Scenario: -javaagent 启动仍可用
前置条件：
- 用户仅配置 `-javaagent:/path/java-sleuth-agent-*-jar-with-dependencies.jar`
- core jar 与 agent jar 同目录（或通过 `-Dsleuth.agent.core.jar` 指定）

期望：
- Bootstrap 能自动定位 core jar 并加载
- 启动失败时给出明确错误信息（缺失 core jar/权限/路径等）

#### Scenario: 兼容性与回退
前置条件：
- 用户环境仍在使用历史“单 jar”版本或仅拷贝了部分新产物

期望：
- 提供清晰的错误提示与迁移指引
-（可选）保留对旧单 jar 的回退逻辑（仅在工程成本可控时实现）

## Risk Assessment

- **Risk:** 两段式加载增加启动链路复杂度，core jar 缺失会导致 attach 失败  
  **Mitigation:** launcher/agent 两侧提供强提示（缺哪个 jar、期望放置位置、可用 override 参数）
- **Risk:** spy/bridge 类若被重复加载（不同 ClassLoader）会导致静态状态不一致  
  **Mitigation:** 通过 `appendToBootstrapClassLoaderSearch` 确保拦截器只由 bootstrap 加载；Bootstrap 本身避免提前触发相关类加载
- **Risk:** 未来新增插桩点误引用 core 内部类或三方库  
  **Mitigation:** 约束：插桩字节码只能引用 `com.javasleuth.monitor.*` 与 JDK 类；在评审/测试中加入校验
