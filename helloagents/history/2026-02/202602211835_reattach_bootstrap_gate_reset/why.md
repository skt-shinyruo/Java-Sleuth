# Change Proposal: reattach_bootstrap_gate_reset

## Requirement Background

当前 Java-Sleuth 的 attach 生命周期在“入口闩锁”处存在语义断裂：

- `com.javasleuth.agent.SleuthAgent` 使用静态 `AtomicBoolean` 做“一次性 attach”闩锁，但没有任何 reset 路径；
- `com.javasleuth.core.agent.core.SleuthAgentCore` 在 shutdown 时只重置了 core 侧 `ATTACHED`，并编排了大量为 `detach → re-attach` 准备的清理逻辑；
- 结果是：即使 core 已经实现了 best-effort 的 runtime close / interceptor 清理 / shutdown 编排，仍会被入口闩锁永久挡死，导致同一 JVM 内 re-attach 基本不可用，且启动失败后的重试也会被静态状态锁死。

该问题会直接降低如下设计/实现的实际价值：

- `SleuthAgentRuntime.close()` 的“回滚式清理”（transformer、session registry、vmtool cache、bootstrap interceptor registries）
- `ShutdownCoordinator` 及相关组件对“stop → restart”的幂等支持
- 文档中关于 detach→re-attach 与测试隔离的承诺

## Change Content

1. 在 `agent` 模块引入**内部门闩类（BootstrapAttachGate）**，统一管理 attach gate 的 enter/release/reset 语义，避免入口类中散落静态状态控制。
2. `SleuthAgent` 的启动失败路径（core jar 缺失、反射调用失败、core 入口抛错等）必须回滚 gate，使用户可在修复参数/环境后重试 attach。
3. `SleuthAgentCore.shutdown()` 在 detach/stop 时触发对 bootstrap gate 的 best-effort reset（跨隔离 classloader 边界通过反射完成），真正打通 `detach → re-attach` 生命周期闭环。
4. 增加单元测试覆盖 reset 行为，并同步更新知识库中关于 attach 生命周期与 re-attach 的 SSOT 描述，避免文档漂移。

## Impact Scope

- **Modules:** agent, core, helloagents/wiki
- **Files:**
  - `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`
  - `agent/src/main/java/com/javasleuth/agent/BootstrapAttachGate.java`（new）
  - `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentCore.java`
  - `core/src/main/java/com/javasleuth/core/agent/core/BootstrapAttachGateReset.java`（new）
  - `core/src/test/java/...`（新增测试与 test double）
  - `helloagents/wiki/modules/agent.md`（同步语义与实现）
  - `helloagents/CHANGELOG.md`（记录修复）
- **APIs:** 无对外协议变更；新增仅供内部反射调用的 `BootstrapAttachGate.resetForReattach()`（internal hook）
- **Data:** 无

## Core Scenarios

### Requirement: detach → re-attach works end-to-end
**Module:** agent/core
同一 JVM 中先 attach 成功，再通过 stop/detach 触发 shutdown，随后再次 attach。

#### Scenario: attach → stop → attach
前置：第一次 attach 已成功启动命令服务与 transformer  
- stop/detach 后，bootstrap gate 与 core gate 都被重置（best-effort）
- 第二次 attach 能再次进入 bootstrap→core 启动链路并启动成功
- 不遗留上一次 attach 的 interceptor/session/transformer 状态

### Requirement: startup failure allows retry
**Module:** agent
第一次 attach 因 core jar 缺失或 core 入口调用失败而未成功启动。

#### Scenario: core jar missing / reflection invoke failed
前置：用户修复 coreJar 参数或补齐产物  
- 首次失败后 gate 被回滚（不永久锁死）
- 重新 attach 可再次尝试启动

### Requirement: repeated attach while running is idempotent
**Module:** agent/core
用户重复触发 attach（可能并发/快速连续）。

#### Scenario: multiple attach requests
- 仅允许一次启动路径进入（CAS gate）
- 其他请求快速返回，不导致崩溃、死锁或资源泄漏

## Risk Assessment

- **Risk:** 跨 classloader 的反射 reset 在部分 JVM/策略下可能失败或行为差异（尤其是非标准 attach 环境）。
  - **Mitigation:** reset 采用 best-effort；优先 system classloader 反射，失败再使用 instrumentation 扫描 loaded classes；全程吞异常，不影响 shutdown 主流程。
- **Risk:** gate 回滚时机选择不当可能引入“未启动成功却保持锁死”或“已启动成功却误解锁”的竞态。
  - **Mitigation:** 仅在 bootstrap 侧明确失败路径回滚；成功调用 core 入口后由 core shutdown 负责 reset；并补充单测验证关键路径。
