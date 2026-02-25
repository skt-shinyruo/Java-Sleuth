# Change Proposal: 安全/权限/协议/采集链路重构（长期演进）

## Requirement Background

Java-Sleuth 作为本地单机排障工具，当前实现已具备较完整的诊断命令体系（watch/trace/tt/jad/redefine 等）与一定的安全控制（HMAC、权限角色、审计日志）。但在“默认配置 + 非预期暴露”的现实场景中，仍存在高风险边界：

1. **默认安全姿态偏向“本机可信”**：默认 `server.bind.address=127.0.0.1` 且 `security.mode=off`，当通过端口转发/容器网络/反向代理等方式被误暴露时，服务端无法感知暴露路径，仍按“off”模型工作。
2. **授权策略存在双来源**：命令角色/危险标记在 `BuiltinCommandProvider` 的 `CommandMeta` 中定义，但 `AuthorizationManager` 又存在命令特判逻辑，长期演进容易出现策略漂移与维护遗漏。
3. **采集链路以“有界队列 + 丢弃”控风险**：该策略能避免 OOM，但在热点方法/高 QPS 场景下会带来结果不稳定与不可预期的丢失，同时采集与格式化仍可能放大 GC 抖动。
4. **Trace 依赖 ThreadLocal 状态**：线程池复用、异常路径、异步边界等场景下，若清理不严谨会导致状态残留/串扰；即使清理正确也会增加线程维度的固定开销。
5. **高危命令天然脆弱**：redefine/retransform/mc/heapdump 等具备强副作用，需要更强的二次确认、审计可追溯与可回滚 SOP。

本方案选择“长期演进重构”路径：在保持本地单机排障体验的前提下，通过协议/会话/权限/采集链路的结构性调整，把安全边界与资源边界从“靠运维正确”升级为“默认更安全、误用也能兜底、并且可观测可回滚”。

## Change Content

1. **默认安全姿态升级**：默认启用 `security.mode=hmac`，并复用 attach 阶段自动引导/生成 secret（保持易用性）；引入显式 `--insecure`/交互确认以进入不安全模式。
2. **协议与握手演进**：在现有 `legacy`/`framed` 基础上统一握手与能力协商（版本、角色、流式、payload 上限、会话标识），把“是否鉴权/是否确认”前置到握手阶段做强约束。
3. **授权策略 SSOT**：以 `CommandMeta` 为唯一权限来源（角色/危险标记/审计/限流），将 `AuthorizationManager` 的命令特判收敛为对 meta 的通用规则（dangerous/ratelimit/audit）。
4. **采集链路会话化与可观测化**：将 watch/trace/tt/stack/monitor 的状态从“全局 map 按 sessionId”演进为“每连接/每会话对象持有”，连接关闭即释放；把丢弃/采样/队列深度通过 metrics/log 明确暴露，并支持运行时调整策略。
5. **Trace 状态生命周期治理**：收敛 ThreadLocal 使用范围，增加深度/大小上限与异常路径强制清理，必要时提供“异步边界降级策略”（例如只采根调用或禁用跨线程关联）。
6. **高危命令二次确认与审计+回滚 SOP**：对标记为 `dangerous` 的命令默认启用二次确认（交互式输入短语/一次性确认 token），审计日志记录“谁/何时/对什么/为什么”，并补齐可回滚操作指引。

## Impact Scope

- **Modules:**
  - `launcher`（CLI/Attach/参数与交互）
  - `agent`（生命周期与资源释放）
  - `command`（协议、握手、Pipeline、缓存、Job、鉴权入口）
  - `security`（认证、授权、审计、限流）
  - `monitor/enhancement`（采集状态会话化、ThreadLocal 治理）
  - `docs`（命令手册、运维 runbook、生产部署说明）
- **Files (expected):**
  - `src/main/resources/sleuth-default.properties`
  - `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
  - `src/main/java/com/javasleuth/agent/SleuthAgent.java`
  - `src/main/java/com/javasleuth/command/CommandProcessor.java`
  - `src/main/java/com/javasleuth/command/CommandPipeline.java`
  - `src/main/java/com/javasleuth/command/CommandRegistry.java`
  - `src/main/java/com/javasleuth/security/AuthorizationManager.java`
  - `src/main/java/com/javasleuth/monitor/*Interceptor.java`
  - `docs/ops/operations-runbook.md`、`docs/ops/production-deployment-guide.md`、`docs/usage/commands.md`
- **APIs:**
  - CLI 新增/调整参数（例如 `--security-mode`、`--insecure`、`--confirm`）
  - 协议握手字段与错误码（向后兼容策略需明确）
- **Data:**
  - 不引入持久化数据；仅新增运行时会话/指标/审计字段

## Core Scenarios

### Requirement: 安全默认姿态与误暴露兜底
**Module:** `security` / `command` / `launcher`
将“默认更安全”作为第一原则，同时为本地单机排障保留低摩擦体验。

#### Scenario: 默认配置下本机即用
在无需手工配置 secret 的情况下，attach 目标 JVM 后可直接建立连接并执行 viewer/operator 命令。
- 期望结果：默认启用安全握手（HMAC 或等效），但用户体验保持“开箱即用”。

#### Scenario: 端口转发/误暴露仍需鉴权或显式放开
当 loopback 服务被转发暴露给外部时，外部无法在未知 secret 的情况下执行命令。
- 期望结果：未完成握手的连接在早期被拒绝，且拒绝原因可审计。
- 期望结果：只有用户显式 `--insecure` 并通过交互确认后，才允许进入不安全模式。

### Requirement: 授权策略单一 SSOT 与一致性
**Module:** `command` / `security`
以 `CommandMeta` 作为权限与风险策略的唯一来源，避免双来源漂移。

#### Scenario: 所有命令权限从 meta 推导
- 期望结果：角色要求、危险标记、是否审计、限流阈值均由 `CommandMeta` 决定。

#### Scenario: 插件命令与内置命令同一套规则
- 期望结果：插件命令必须提供 meta，否则默认降权或拒绝加载（策略需在 how.md 中明确）。

### Requirement: 采集链路可观测可控与资源隔离
**Module:** `monitor` / `command`
在“可控开销”前提下提升可预测性与可观测性。

#### Scenario: 丢弃与采样可见
- 期望结果：watch/trace/tt/stack 的 published/dropped/evicted/sampled 指标可通过 metrics/status 查询。

#### Scenario: 会话关闭即释放资源
- 期望结果：连接断开后，采集队列与状态立即释放；无连接残留导致的内存增长可被避免或自动回收。

### Requirement: Trace 状态生命周期与 ThreadLocal 风险收敛
**Module:** `monitor`

#### Scenario: 异常路径强制清理
- 期望结果：被增强方法抛异常时，不会遗留 per-thread 状态与 trace 栈。

#### Scenario: 线程池复用不串扰
- 期望结果：不同请求/不同 traceId 不会因线程复用而串线；必要时提供“异步降级模式”。

### Requirement: 高危命令二次确认审计与回滚 SOP
**Module:** `command` / `security` / `docs`

#### Scenario: 默认需要二次确认
- 期望结果：对 `dangerous` 命令（redefine/retransform/mc/heapdump/stop/reset 等）默认要求确认（交互短语/一次性 token）。

#### Scenario: 审计可追溯与可回滚
- 期望结果：审计日志包含操作人、角色、目标类/方法、参数摘要、确认方式与理由；文档提供回滚与应急处理流程。

## Risk Assessment

- **Risk: 默认行为变更导致现有脚本不可用**
  - **Mitigation:** 提供向后兼容开关（例如 `--security-mode=off --insecure`），并在 CLI 输出明确迁移提示。
- **Risk: 安全 secret 处理不当导致泄露**
  - **Mitigation:** secret 默认不在 stdout 明文输出；仅在明确指令下显示；支持短期一次性 token。
- **Risk: 协议演进造成客户端/服务端版本不兼容**
  - **Mitigation:** 设计握手版本协商与“降级到 legacy”路径；提供明确错误码与指导。
- **Risk: 采集链路重构引入性能回归**
  - **Mitigation:** 保持有界队列 + 采样为默认；新增指标与压测脚本；关键路径避免额外分配。
- **Risk: 高危命令引发线上副作用**
  - **Mitigation:** 强制二次确认 + 审计 + 回滚 SOP；默认降权；对危险操作设置更严格的限流与授权。  

