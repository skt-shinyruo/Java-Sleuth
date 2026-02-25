# 模块：agent（Java Agent Bootstrap & Core 隔离加载）

## 1. 模块目的

Java Agent 的最大工程风险之一是 **依赖碰撞**：诊断工具往往需要 ASM/Jackson/CFR/JLine 等第三方库，而被诊断应用也可能携带同名依赖但版本不同。

如果 agent 依赖与业务依赖落在同一类加载器可见范围内（或 parent-first 解析路径上），会导致：

- `LinkageError` / `NoSuchMethodError` 等运行时崩溃
- 诊断行为随目标应用依赖变化而不稳定
- 难以复现的“环境相关问题”（CI/本地/线上差异）

因此本项目采用 **bootstrap → container → core 的隔离加载**：

- `bootstrap`：bootstrap 可见桥接层（spy/bridge，JDK-only），承载增强代码直接调用的拦截器与共享模型
- `agent`：bootstrap 入口（最小化依赖）
- `container`：隔离类加载域内的 runtime container（fat-jar，包含三方依赖），通过隔离类加载器加载
- `core`：agent-core 核心实现库（命令服务端/诊断能力/transformer 等），由 container 装配启动

## 2. 入口类

- Bootstrap（模块 `agent`）
  - `com.javasleuth.agent.SleuthAgent`
  - 负责 `premain/agentmain`，并把执行转发到隔离域内的 container
  - attach gate（SSOT）：`com.javasleuth.bootstrap.agent.CoreClassLoaderRegistry`
    - bootstrap 侧登记本次 attach 创建的隔离 `URLClassLoader`（CAS）
    - shutdown 后 best-effort 关闭并清引用（同时作为 detach→re-attach 的闩锁语义）
    - `SleuthAgent` 通过 `Class.forName(..., null)` 反射调用，确保使用 BootstrapClassLoader 版本（避免跨 ClassLoader 状态分裂）
  - `com.javasleuth.agent.BootstrapAttachGate`
    - legacy fallback：当 registry bridge 不可用时使用 CAS gate，避免重复 attach；正常路径以 registry gate 为准
- Container（模块 `container`）
  - `com.javasleuth.container.SleuthAgentContainerEntrypoint`
  - 作为隔离类加载域内的 composition root：解析/应用 agentArgs、启动 per-attach runtime，并在 shutdown 时收口资源回收与闩锁 reset（detach→re-attach）
  - 入口逻辑收敛：与 `SleuthAgentCore` 共享 `com.javasleuth.core.agent.core.SleuthAgentEntrypointSupport`（attach/shutdown SSOT），避免行为分叉
  - shutdown 时会回调 `CoreClassLoaderRegistry.onCoreShutdown(SleuthAgentContainerEntrypoint.class.getClassLoader())` 释放隔离 ClassLoader（best-effort）
- Core（模块 `core`，包名 `com.javasleuth.core.agent.core`）
  - `com.javasleuth.core.agent.core.SleuthAgentCore`
  - legacy 入口（保留用于兼容/测试基座；默认 attach 路径由 container 入口接管）
  - 入口逻辑收敛：`SleuthAgentCore` 自身仅保留稳定 API（如 `getInstrumentation/getTransformer`），启动/关闭委托给 `SleuthAgentEntrypointSupport`
  - 约束：`SleuthAgentCore` 作为 composition root，不再持有大量 static 可变字段；运行时状态收口到 `com.javasleuth.core.agent.runtime.SleuthAgentRuntime`（per attach），除 instrumentation/transformer/processor 外亦包含 `JobManager`/`VmToolSessionRegistry` 等关键会话/后台状态，统一 `close()` 编排以支持 detach→re-attach 与测试隔离
  - 全局清理收口：`com.javasleuth.core.agent.runtime.AgentGlobalState` 作为 bootstrap interceptor 静态注册表清理的 SSOT（best-effort），供 `SleuthAgentRuntime.close()` / `SleuthAgentCore.shutdown()` fallback / `reset` 命令复用，降低清理入口蔓延
  - `com.javasleuth.core.agent.core.BootstrapAttachGateReset`
    - best-effort 反射调用 `com.javasleuth.agent.BootstrapAttachGate.resetForReattach()`（在 `shutdown()` finally 中触发），避免 bootstrap 入口闩锁永久锁死

隔离落地约束（当前构建产物验证）：

- `agent` bootstrap jar **不包含** ASM/Jackson/CFR 等核心三方库（避免被 agent 入口类加载器意外触发加载）
- `agent` bootstrap jar append 到 bootstrap 搜索路径的内容仅包含 `bootstrap` 模块的 spy/bridge（例如 `com.javasleuth.bootstrap.monitor/*`、`com.javasleuth.bootstrap.data/*`、值快照工具等），避免把 `config/security/protocol` 等能力提升为 bootstrap 可见
- 上述三方库集中在 `container` 的 jar-with-dependencies 中，由隔离类加载器加载运行

## 3. 隔离加载策略

### 3.1 核心原则

bootstrap 入口必须做到：

- 不直接依赖（也不触发加载）ASM/Jackson/CFR/JLine 等第三方库
- 只使用 JDK 能力定位自身 jar、创建隔离类加载器、反射调用 core 入口（jar 定位通过 `bootstrap` 层 `JarLocator` 作为 SSOT）

### 3.2 具体做法（当前代码）

- 在 `SleuthAgent` 中创建 `URLClassLoader`，并将 parent 设为 `null`
  - 使 core 的依赖解析不受业务 `AppClassLoader` 影响（避免 parent-first 碰撞）
- 使用反射方式加载/调用 `SleuthAgentContainerEntrypoint` 的入口方法
  - jar 定位（SSOT）：`bootstrap` 模块的 `JarLocator` 优先定位 `container` fat-jar
    - agentArgs：`containerJar=/path/to/java-sleuth-container-*-jar-with-dependencies.jar`
    - 或 override：`-Dsleuth.agent.container.jar=...` / `SLEUTH_AGENT_CONTAINER_JAR=...`

### 3.3 预期效果

- **业务不可见**：业务代码/业务类加载器不会“看到”container 内的第三方依赖
- **版本稳定**：container 使用自身携带的依赖版本，避免被业务依赖“劫持”
- **故障域收缩**：依赖冲突从“影响整个目标 JVM”降低为“隔离域内可控”

## 4. 代价与缺点（必须知晓）

隔离加载并非零成本：

- **实现复杂度上升**：需要维护跨类加载器调用边界（反射/桥接接口/序列化）
- **调试成本上升**：排查 classloader 相关问题更困难（尤其是资源加载与 SPI）
- **内存占用增加**：同名类在不同 classloader 下可能出现多份副本
- **与业务交互需更谨慎**：
  - 不能直接强依赖业务三方库类型（避免把业务依赖“拉进来”）
  - 需要显式选择目标 `ClassLoader`（如基于线程上下文或目标类自身的 loader）

结论：该策略以 **“稳定性/兼容性优先”** 为价值取舍，适合诊断类工具的长期演进。
