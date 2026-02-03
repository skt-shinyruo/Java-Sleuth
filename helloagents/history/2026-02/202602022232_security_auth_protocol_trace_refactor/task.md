# Task List: 安全/权限/协议/采集链路重构（长期演进）

Directory: `helloagents/plan/202602022232_security_auth_protocol_trace_refactor/`

---

## 1. 默认安全姿态与误暴露兜底（握手/协议/CLI）
- [√] 1.1 调整默认配置：默认启用 `security.mode=hmac`，保留 `server.bind.address=127.0.0.1` 与 attach bootstrap secret 机制，更新 `src/main/resources/sleuth-default.properties`，verify why.md#requirement-安全默认姿态与误暴露兜底- [√] 1.2 为 CLI 增加显式不安全模式：在 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java` 增加 `--insecure` 与交互确认（短语确认），并将选择结果写入运行时配置（或握手能力声明），verify why.md#requirement-安全默认姿态与误暴露兜底- [√] 1.3 统一握手与能力协商：在 `src/main/java/com/javasleuth/command/CommandProcessor.java` 与 `src/main/java/com/javasleuth/command/protocol/*` 增加“未握手不处理命令”的强约束（包含版本协商、角色声明、payload/line 上限），verify why.md#requirement-安全默认姿态与误暴露兜底，depends on task 1.1- [√] 1.4 强化 HMAC/会话认证：在 `src/main/java/com/javasleuth/security/RequestSecurityManager.java` 收敛认证入口（HMAC、nonce/timestamp window、错误码/审计），并与握手阶段集成，verify why.md#requirement-安全默认姿态与误暴露兜底，depends on task 1.3- [√] 1.5 增补测试：在 `src/test/java/com/javasleuth/command/CommandProcessorSecurityBoundaryTest.java` 与 `src/test/java/com/javasleuth/security/RequestSecurityManagerTest.java` 增加“未握手拒绝/不安全模式需显式确认/默认启用 HMAC”的用例，verify why.md#requirement-安全默认姿态与误暴露兜底，depends on task 1.2
## 2. 授权策略单一 SSOT 与一致性（去特判/以 meta 驱动）
- [√] 2.1 将授权规则完全收敛到 `CommandMeta`：调整 `src/main/java/com/javasleuth/security/AuthorizationManager.java`，移除按命令名的策略分支，统一基于 `CommandMeta.requiredRole/dangerous/requiresAudit/maxExecutionsPerMinute` 判定，verify why.md#requirement-授权策略单一-ssot-与一致性- [√] 2.2 明确 meta 来源与插件策略：在 `src/main/java/com/javasleuth/command/CommandRegistry.java` 规范“插件命令必须提供 meta”的策略（否则拒绝加载或降权），并将策略写入文档，verify why.md#requirement-授权策略单一-ssot-与一致性，depends on task 2.1- [√] 2.3 回归/新增单测：为 meta 驱动授权补齐测试覆盖（包含危险命令与限流），优先落在 `src/test/java/com/javasleuth/command/CommandProcessorSecurityBoundaryTest.java` 与新增 `src/test/java/com/javasleuth/security/AuthorizationManagerMetaPolicyTest.java`，verify why.md#requirement-授权策略单一-ssot-与一致性，depends on task 2.1
## 3. 采集链路会话化与资源隔离（避免全局残留 + 指标化）
- [√] 3.1 引入会话对象模型：新增 `src/main/java/com/javasleuth/command/session/ClientSession.java` 与 `src/main/java/com/javasleuth/command/session/SessionRegistry.java`（或同等结构），并在 `src/main/java/com/javasleuth/command/CommandProcessor.java` 连接生命周期中创建/关闭会话，verify why.md#requirement-采集链路可观测可控与资源隔离- [√] 3.2 将采集状态挂载到会话：重构 `src/main/java/com/javasleuth/monitor/*Interceptor.java`，从静态全局 map 演进为“通过 sessionId 查找会话状态对象”，连接关闭即释放，verify why.md#requirement-采集链路可观测可控与资源隔离，depends on task 3.1- [ ] 3.3 指标与可观测性统一：增强 `src/main/java/com/javasleuth/command/impl/MetricsCommand.java` 与/或 `src/main/java/com/javasleuth/command/impl/StatusCommand.java` 输出 dropped/evicted/sampled 等指标，并在必要时写入审计，verify why.md#requirement-采集链路可观测可控与资源隔离，depends on task 3.2
- [ ] 3.4 资源回收与 TTL：为 `SessionRegistry` 增加 TTL/上限/回收策略，并补齐断连/异常场景的清理（含 jobs 与监控队列），verify why.md#requirement-采集链路可观测可控与资源隔离，depends on task 3.1
- [ ] 3.5 增补测试：新增 `src/test/java/com/javasleuth/monitor/SessionLifecycleCleanupTest.java`（或同等测试）验证“断连后队列被清理、无残留增长、指标正确”，verify why.md#requirement-采集链路可观测可控与资源隔离，depends on task 3.2

## 4. Trace 状态生命周期与 ThreadLocal 风险收敛（强清理 + 上限 + 可降级）
- [√] 4.1 收敛 Trace ThreadLocal 结构：重构 `src/main/java/com/javasleuth/monitor/TraceInterceptor.java`，将 `ThreadLocal<Map<...>>` 演进为有上限的轻量结构，并在异常/完成路径强制清理（必要时 `ThreadLocal.remove()`），verify why.md#requirement-trace-状态生命周期与-threadlocal-风险收敛- [√] 4.2 提供异步边界降级策略：在 `src/main/resources/sleuth-default.properties` 与 `src/main/java/com/javasleuth/config/ProductionConfig.java` 增加相关配置项（例如只采根调用/禁用跨线程关联），并在文档明确限制，verify why.md#requirement-trace-状态生命周期与-threadlocal-风险收敛，depends on task 4.1- [√] 4.3 增补测试：在 `src/test/java/com/javasleuth/monitor/TraceInterceptorSamplingTest.java` 增加异常路径/线程复用相关用例，确保状态不残留且不串扰，verify why.md#requirement-trace-状态生命周期与-threadlocal-风险收敛，depends on task 4.1
## 5. 高危命令二次确认、审计增强与回滚 SOP
- [√] 5.1 统一危险命令确认机制：在 `src/main/java/com/javasleuth/command/CommandPipeline.java`（或 `CommandProcessor`）基于 `CommandMeta.dangerous` 增加确认门槛（交互短语/一次性 token），并提供 CLI 参数桥接（如 `--confirm-token`），verify why.md#requirement-高危命令二次确认审计与回滚-sop- [√] 5.2 审计增强：扩展 `src/main/java/com/javasleuth/security/AuditLogger.java`，记录确认方式、理由（可选）、目标信息摘要与执行结果，并确保敏感信息默认脱敏，verify why.md#requirement-高危命令二次确认审计与回滚-sop，depends on task 5.1- [√] 5.3 文档与 SOP：更新 `docs/ops/operations-runbook.md`、`docs/ops/production-deployment-guide.md`、`docs/usage/commands.md` 增补二次确认、回滚流程与常见故障排查，verify why.md#requirement-高危命令二次确认审计与回滚-sop，depends on task 5.2- [√] 5.4 增补测试：为危险命令确认与审计补齐测试用例（可落在 `src/test/java/com/javasleuth/command/CommandProcessorSecurityBoundaryTest.java` 与新增审计测试），verify why.md#requirement-高危命令二次确认审计与回滚-sop，depends on task 5.2
## 6. Security Check
- [ ] 6.1 执行安全检查（输入校验、鉴权与权限边界、secret/日志脱敏、限流、插件加载策略、误暴露兜底路径），并输出结论与待办

## 7. Documentation Update（知识库同步）
- [√] 7.1 更新知识库：`helloagents/wiki/arch.md`、`helloagents/wiki/api.md`、`helloagents/wiki/overview.md` 补齐握手/权限 SSOT/会话化采集的架构与使用说明，verify why.md 全部 requirements- [√] 7.2 更新 `helloagents/CHANGELOG.md` 记录默认安全行为变更、兼容策略与新增参数
## 8. Testing
- [√] 8.1 运行 `mvn test` 并修复与本变更直接相关的失败用例- [ ] 8.2 最小手工回归：本地启动目标 JVM + attach + 执行 `help/status/metrics/watch/trace/tt` 与至少 1 个危险命令确认流程（可在文档中给出推荐步骤）

