# 技术设计：Agent 依赖隔离（两段式 Agent + 隔离 ClassLoader）

## Technical Solution

### Core Technologies
- Java Instrumentation（`agentmain` / `premain`）
- Attach API（Launcher 侧注入）
- `Instrumentation#appendToBootstrapClassLoaderSearch`
- `URLClassLoader`（parent = bootstrap/null）实现依赖隔离
- Maven 多模块 + `maven-assembly-plugin` 产出双 fat-jar

### Implementation Key Points
1. **新增 bootstrap agent 模块（`java-sleuth-agent`）**
   - 入口类：`com.javasleuth.agent.SleuthAgent`
   - 行为：
     - 最小化自身依赖（只用 JDK 类，避免提前加载 `foundation`/`monitor`，防止重复加载导致状态割裂）
     - 将自身 jar（包含 `foundation` + `com.javasleuth.monitor.*`）append 到 bootstrap classloader search
     - 解析并定位 core jar（优先 agentArgs `coreJar=`，其次 `-Dsleuth.agent.core.jar` / `SLEUTH_AGENT_CORE_JAR`，最后按相对路径扫描同目录）
     - 用隔离 ClassLoader 加载 core，并通过反射调用 core 的 `agentmain`
2. **core 实现模块（`java-sleuth-agent-core`）**
   - 入口类：`com.javasleuth.agent.core.SleuthAgentCore`（由 bootstrap 调用）
   - 依赖包含 ASM/Jackson/CFR/RE2J 等，但仅在隔离 ClassLoader 内可见
   - 不再在 Manifest 中声明 `Agent-Class/Premain-Class`（避免被误当成可注入 agent）
   - Manifest 增加标记：`Sleuth-Agent-Core: true`，便于 launcher 自动定位
3. **monitor spy/bridge 下沉**
   - `com.javasleuth.monitor.*Interceptor` 迁移到 `foundation`（或确保其随 bootstrap append 到 bootstrap）
   - 插桩字节码继续只调用 `com.javasleuth.monitor.*` 静态方法，确保业务侧不需要感知 core 依赖
4. **Launcher 注入参数扩展**
   - `JarLocator` 新增 `locateAgentCoreJar(...)`
   - `vm.loadAgent(agentBootstrapJar, "coreJar=...;...")`

## Architecture Design

```mermaid
flowchart TD
    L[Launcher (outside target JVM)] -->|Attach API loadAgent| BA[Bootstrap Agent jar]
    BA -->|appendToBootstrapClassLoaderSearch| BCL[Bootstrap ClassLoader]
    BA -->|URLClassLoader(parent=bootstrap)| CL[Isolated Core ClassLoader]
    CL --> AC[Agent Core (ASM/Jackson/CFR...)]
    AC -->|addTransformer| INST[Instrumentation]
    INST --> APP[Target App Classes]
    APP -->|bytecode invokes| SPY[com.javasleuth.monitor.* (in bootstrap)]
```

## Architecture Decision ADR

### ADR-001：采用“两段式 Agent + 隔离 ClassLoader”替代单 jar fat-jar 注入
**Context:** Agent fat-jar 把 ASM/Jackson/CFR 等原包名依赖带入目标 JVM，可见性过大且易与业务依赖发生版本碰撞；同时业务类的插桩回调需要稳定可见的 spy/bridge。  
**Decision:** 将 agent 拆为 bootstrap 与 core 两个产物；bootstrap 将 spy/bridge append 到 bootstrap classloader，并用隔离 ClassLoader 加载 core。  
**Rationale:** 最大化隔离，最接近 Arthas 的成熟实践；对业务依赖面影响最小；可控的可见性边界更易维护。  
**Alternatives:**  
- 方案：shade + relocate 三方依赖 → 仍然需要处理“业务字节码回调可见性”与潜在重复加载问题；并且 relocate 对反射/ServiceLoader 兼容性要求更高。  
**Impact:** 部署产物从 2 个 jar（launcher+agent）增加为 3 个 jar（launcher + agent-bootstrap + agent-core），需更新运维说明与脚本；启动链路复杂度上升，需要更强的错误提示与自动定位策略。

## Security and Performance

- **Security:**
  - 限制插桩字节码仅引用 `com.javasleuth.monitor.*` 与 JDK 类型，避免把三方库暴露给业务 ClassLoader
  - core jar 路径通过 launcher/系统属性传入，严格校验文件存在与可读性
  - 失败策略：bootstrap 失败时不影响业务进程继续运行（仅 attach 失败）
- **Performance:**
  - spy/bridge 逻辑必须 best-effort、低开销、失败不影响业务
  - 隔离 ClassLoader 仅在 attach 时创建一次，避免频繁创建导致 metaspace 压力

## Testing and Deployment

- **Testing:**
  - `mvn test`：单测覆盖 JarLocator、agent 参数解析、monitor 拦截器稳定性
  - `mvn -DskipTests package`：验证产物与 Manifest
  -（可选）用 `examples` 模块启动一个 demo 应用，运行 launcher attach，执行 watch/trace/jad/metrics 等命令回归
- **Deployment:**
  - 发布目录建议同时放置：
    - `java-sleuth-launcher-*-jar-with-dependencies.jar`
    - `java-sleuth-agent-*-jar-with-dependencies.jar`（bootstrap）
    - `java-sleuth-agent-core-*-jar-with-dependencies.jar`（core）
  - `-javaagent` 场景：只指定 bootstrap agent jar，core jar 需同目录或用 `-Dsleuth.agent.core.jar` 指定
