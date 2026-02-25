# Technical Design: reattach_bootstrap_gate_reset

## Technical Solution

### Core Technologies
- Java 8（项目 baseline）
- `java.lang.instrument.Instrumentation`
- 反射调用（`Method.invoke`，跨隔离 classloader 边界）
- `java.util.concurrent.atomic.AtomicBoolean`（attach gate）

### Implementation Key Points

#### 1) 引入内部门闩类：`com.javasleuth.agent.BootstrapAttachGate`（agent 模块）

目标：将 `SleuthAgent` 中的“一次性 attach”语义从裸 `static AtomicBoolean` 提升为**可控且可 reset 的 gate**。

建议 API（均为 JDK-only，无第三方依赖）：

- `boolean tryEnter()`：CAS `false → true`，用于防止重复 attach 并发进入。
- `void releaseOnFailure()`：仅供 bootstrap 侧启动失败回滚。
- `void resetForReattach()`：供 core shutdown best-effort 调用，打通 detach→re-attach。
- `boolean isAttached()`：辅助日志/调试（可选）。

约束：
- gate 类保持极小、稳定、无外部依赖；
- `resetForReattach()` 作为 internal hook，避免被当作对外 API 承诺（文档中注明“内部反射用途”）。

#### 2) 改造 `SleuthAgent`：使用 gate + 明确失败回滚

改造点：

- 移除/替换 `SleuthAgent` 内部的 `private static final AtomicBoolean ATTACHED`；
- `agentmain(...)` 先 `tryEnter()`，失败则快速返回；
- `bootstrap(...)` 在以下场景必须回滚 gate（`releaseOnFailure()`）：
  - core jar 定位失败（`JarLocator.locateAgentCoreJar(...)` 返回 null）
  - core entrypoint 类加载/反射方法查找失败
  - 反射调用 core 入口抛错（`InvocationTargetException` 等）
- 当 core entrypoint 反射调用成功返回后，不在 bootstrap 侧释放 gate（保持“已 attach”状态），由 core shutdown 负责 reset。

额外建议：
- 在 bootstrap 异常路径上只输出必要错误信息，避免将堆栈/敏感信息默认写入 stderr（保留 `sleuth.agent.bootstrap.debug` 开关）。

#### 3) core shutdown 时 reset bootstrap gate（best-effort）

在 `SleuthAgentCore.shutdown()` 的 finally 中增加 bootstrap gate reset，确保无论 runtime 是否初始化成功、shutdown 被调用多少次，都能尽量回到“可 re-attach”的状态。

实现建议：新增 helper `BootstrapAttachGateReset`（core 模块），提供：

- `resetBestEffort(Instrumentation instrumentation)`

reset 策略（按优先级）：

1. **system classloader 反射（优先）**
   - `Class.forName("com.javasleuth.agent.BootstrapAttachGate", false, ClassLoader.getSystemClassLoader())`
   - 调用 `resetForReattach()`（public static）
   - 优点：不依赖 instrumentation，单测可直接覆盖；生产环境下也更直观。

2. **instrumentation 扫描 loaded classes（fallback）**
   - `instrumentation.getAllLoadedClasses()` 遍历匹配 class name
   - 通过 `getMethod("resetForReattach")` 或 `getDeclaredMethod` 调用
   - 优点：即便 system classloader 路径异常，也可在“类已加载但非系统可直接加载”的边缘场景中恢复。

约束：
- 全程 best-effort：吞异常，不改变 shutdown 主流程语义；
- reset 仅针对固定 FQN，避免把反射入口做成“可注入参数”的风险点。

## Architecture Decision ADR

### ADR-001: 跨隔离 ClassLoader 的 bootstrap attach gate reset 机制

**Context:** core 由 `URLClassLoader(parent=null)` 隔离加载，无法以编译期依赖方式直接引用 `agent` 模块类；而 detach→re-attach 需要在 core shutdown 时重置入口闩锁。

**Decision:** 在 `agent` 模块新增极小 gate 类 `com.javasleuth.agent.BootstrapAttachGate`，暴露 `resetForReattach()` 作为 internal hook；core 侧在 shutdown 时通过反射 best-effort 调用（system classloader 优先，instrumentation 扫描兜底）。

**Rationale:**
- 保持 bootstrap（spy/bridge）模块边界不扩张（仍为 JDK-only 且最小暴露面）
- 不让 core 在编译期依赖 agent（保持隔离加载策略）
- reset 路径集中到 shutdown（生命周期语义完整，便于测试与文档 SSOT）

**Alternatives:**
- 将闩锁迁移到 `bootstrap` 模块（优点：core 可直接调用；缺点：扩大 bootstrap 可见面，需重新评估边界）
- 在 `SleuthAgent` 上直接增加 public reset 方法（优点：简单；缺点：入口类 API 污染更明显）
- 保持一次性闩锁不修复（缺点：现有 detach→re-attach 清理逻辑价值大幅下降）

**Impact:**
- 增加一个内部类与一次反射调用点（仅在 shutdown 触发）
- detach→re-attach 语义对齐，减少测试与文档漂移

## Security and Performance

- **Security:**
  - 反射调用目标为固定 FQN + 固定方法名，不接受外部输入；
  - reset 逻辑 best-effort，不应因反射失败导致 shutdown 异常；
  - 默认日志保持克制，仅在 debug 开关开启时输出堆栈。
- **Performance:**
  - reset 仅发生在 shutdown/detach 路径；
  - instrumentation 扫描仅作为 fallback，属于低频路径，可接受。

## Testing and Deployment

- **Testing:**
  - 单测覆盖 `BootstrapAttachGateReset.resetBestEffort(null)` 在 system classloader 路径可用时能正确 reset；
  - 单测覆盖 `SleuthAgentCore.shutdown()` 会触发 reset（在 runtime 为空时仍能执行）。
- **Deployment:**
  - 无配置迁移；
  - attach/detach 语义变更为“支持 re-attach”（修复型增强），预期向后兼容。

