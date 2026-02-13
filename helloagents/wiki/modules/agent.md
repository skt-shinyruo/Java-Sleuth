# 模块：agent（Java Agent Bootstrap & Core 隔离加载）

## 1. 模块目的

Java Agent 的最大工程风险之一是 **依赖碰撞**：诊断工具往往需要 ASM/Jackson/CFR/JLine 等第三方库，而被诊断应用也可能携带同名依赖但版本不同。

如果 agent 依赖与业务依赖落在同一类加载器可见范围内（或 parent-first 解析路径上），会导致：

- `LinkageError` / `NoSuchMethodError` 等运行时崩溃
- 诊断行为随目标应用依赖变化而不稳定
- 难以复现的“环境相关问题”（CI/本地/线上差异）

因此本项目采用 **bootstrap → core 的隔离加载**：

- `bootstrap`：bootstrap 可见桥接层（spy/bridge，JDK-only），承载增强代码直接调用的拦截器与共享模型
- `agent`：bootstrap 入口（最小化依赖）
- `core`：agent-core（功能主体 + 三方依赖），通过隔离类加载器加载

## 2. 入口类

- Bootstrap（模块 `agent`）
  - `com.javasleuth.agent.SleuthAgent`
  - 负责 `premain/agentmain`，并把执行转发到 core
- Core（模块 `core`，包名 `com.javasleuth.agent.core`）
  - `com.javasleuth.agent.core.SleuthAgentCore`
  - 负责启动命令服务端与诊断能力

隔离落地约束（当前构建产物验证）：

- `agent` bootstrap jar **不包含** ASM/Jackson/CFR 等核心三方库（避免被 agent 入口类加载器意外触发加载）
- `agent` bootstrap jar append 到 bootstrap 搜索路径的内容仅包含 `bootstrap` 模块的 spy/bridge（例如 `com.javasleuth.monitor/*`、`com.javasleuth.data/*`、值快照工具等），避免把 `config/security/protocol` 等能力提升为 bootstrap 可见
- 上述三方库集中在 `agent-core` 的 jar-with-dependencies 中，由隔离类加载器加载运行

## 3. 隔离加载策略

### 3.1 核心原则

bootstrap 入口必须做到：

- 不直接依赖（也不触发加载）ASM/Jackson/CFR/JLine 等第三方库
- 只使用 JDK 能力定位自身 jar、创建隔离类加载器、反射调用 core 入口

### 3.2 具体做法（当前代码）

- 在 `SleuthAgent` 中创建 `URLClassLoader`，并将 parent 设为 `null`
  - 使 core 的依赖解析不受业务 `AppClassLoader` 影响（避免 parent-first 碰撞）
- 使用反射方式加载/调用 `SleuthAgentCore` 的入口方法

### 3.3 预期效果

- **业务不可见**：业务代码/业务类加载器不会“看到”agent-core 的第三方依赖
- **版本稳定**：agent-core 使用自身携带的依赖版本，避免被业务依赖“劫持”
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
