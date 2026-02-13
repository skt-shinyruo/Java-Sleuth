# 架构总览（SSOT）

## 1. 端到端工作流

Java-Sleuth 的主链路可以抽象为：

1. `launcher` 发现/选择目标 JVM（进程选择）
2. `launcher` 使用 Attach API 将 `agent` 注入目标 JVM
3. 目标 JVM 内 `agent` 作为 bootstrap 入口启动（`premain/agentmain`）
4. `agent` 引导隔离加载 `agent-core`（见下文“依赖隔离”）
5. `agent-core` 在目标 JVM 内启动命令服务端（Command Server）
6. `launcher` 作为客户端执行握手/升级协议并进入交互（JLine）或 headless 执行（脚本化）

## 2. 模块与边界

多模块划分的目的：把 **“注入/生命周期/协议/UI/命令执行”** 拆成可替换、可测试的组件边界，降低演进成本。

- `bootstrap`
  - **Bootstrap 可见桥接层（spy/bridge，JDK-only）**
  - 承载增强代码直接调用的拦截器与共享模型：`com.javasleuth.monitor/*`、`com.javasleuth.data/*`
  - 承载轻量共享工具（值快照/环形缓冲/产物定位/agentArgs 解析）：`com.javasleuth.util/*`
- `foundation`
  - 非 bootstrap 可见的低层基础能力（JDK-only）：config/security/protocol 等
- `agent`（Bootstrap）
  - Java Agent 入口：`com.javasleuth.agent.SleuthAgent`
  - 目标：**尽量少依赖，仅负责把 core 隔离加载并转发执行**
- `core`（Agent Core）
  - Java Agent Core 入口：`com.javasleuth.agent.core.SleuthAgentCore`
  - 目标：在目标 JVM 内提供诊断能力与命令服务端
- `launcher`
  - 本机启动器入口：`com.javasleuth.launcher.SleuthLauncher`
  - 目标：进程选择、Attach、协议客户端、交互 UI（可插拔运行模式）

## 3. 依赖隔离（目标 JVM 视角）

### 3.1 风险背景

Java Agent 的第三方库（ASM/Jackson/JLine/CFR 等）如果与业务依赖在同一命名空间/可见范围内，会导致：

- 业务依赖版本与 agent 依赖版本碰撞（类签名/方法签名不兼容）
- agent 运行时行为受业务 classpath/parent-first 加载顺序影响
- 调试与定位成本上升（问题呈现与目标应用依赖强相关）

### 3.2 当前策略

采用 **“bootstrap 入口最小化 + core 隔离加载”**：

- `agent`（bootstrap）负责创建隔离的 `URLClassLoader`（parent = `null`）加载 `agent-core`
- `agent-core` 及其第三方依赖在隔离类加载器内运行
- `agent` 会将自身产物 append 到 bootstrap 搜索路径，但该 jar 仅包含 `bootstrap` 模块的 spy/bridge（避免把 config/security/protocol 等能力提升为 bootstrap 可见）
- 与业务 `AppClassLoader` 的类命名空间分离，从而降低依赖碰撞概率

> 注：隔离不是“零耦合”。Agent 仍需要与 JDK/Instrumentation 交互；对业务类的交互一般通过 `Instrumentation`、反射与显式选择目标 `ClassLoader` 的方式完成（避免直接强依赖业务依赖）。

## 4. Launcher 去 God class：组合根（composition root）

`SleuthLauncher` 被约束为 **组合根**：只负责参数解析与组件装配，避免把“发现/Attach/安全确认/握手/IO 循环/UI”全部塞进一个类里。

关键拆分方向：

- `cli`：参数与运行模式（interactive/headless）
- `jvm`：进程发现与选择策略
- `attach`：Attach API 抽象与 Agent 参数构造
- `client`：协议客户端与握手协商
- `shell`：交互 UI 与 headless 编排

## 5. CommandProcessor 去 God class：生命周期组件化

服务端侧（目标 JVM 内）的命令处理从单一巨型编排类拆分为：

- `ServerBootstrapper`：自举边界（配置/安全/绑定）
- `ConnectionAcceptor`：accept 循环与连接限流/拒绝策略
- `ShutdownCoordinator`：优雅/紧急关闭与幂等保护
- `CommandProcessor`：facade（装配并委托上述组件，保留对外 API 的稳定性）

这样做的收益：

- 更容易做单测（连接上限、shutdown 幂等等）
- 更容易替换实现（不同网络模型、不同协议 handler）
- 修改局部能力时不牵一发动全身
