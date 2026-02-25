# Task List: 命令执行 Pipeline Step/Interceptor 链重构

Directory: `helloagents/history/2026-02/202602051031_command_pipeline_step_chain/`

---

## 1. command - Pipeline 框架与 precheck 链
- [√] 1.1 新增 pipeline 基础抽象（Step/Chain/Invocation/Outcome）到 `src/main/java/com/javasleuth/command/pipeline/`，verify why.md#scenario-s1-sync-command-execution
- [√] 1.2 将 `CommandPipeline.precheck(...)` 重构为 Step 链（ValidateStep/AuthzStep/DangerousConfirmStep），并保持 args 处理语义一致，verify why.md#scenario-s3-confirm-token-is-not-part-of-validationauthz
- [-] 1.3 补齐/调整 precheck 相关单测（确认 confirm args 剥离、deny 时 normalizedArgs 行为），verify why.md#scenario-s3-confirm-token-is-not-part-of-validationauthz

## 2. command - 同步执行链（cache/impact/timeout/sanitize）
- [√] 2.1 将 `CommandPipeline.executePrechecked(...)` 重构为执行 Step 链（CacheStep/ImpactPermitStep/TimeoutExecutorStep/OutputSanitizeStep），verify why.md#scenario-s1-sync-command-execution
- [√] 2.2 保持缓存隔离与绕过逻辑（clientId key、session 不缓存、dashboard realtime 绕过），并补齐必要测试，verify why.md#scenario-s5-cache-key-includes-clientid
- [√] 2.3 保持高影响命令 single-flight 语义与 permit 释放路径（含 executor rejection/timeout），并补齐必要测试，verify why.md#scenario-s4-high-impact-single-flight

## 3. command - 流式执行链（streaming 语义一致）
- [√] 3.1 将 `CommandPipeline.executeStreamPrechecked(...)` 重构为流式执行 Step 链（ImpactPermitStep/TimeoutStreamExecutorStep/GuardedStreamSinkStep），verify why.md#scenario-s2-stream-command-execution
- [√] 3.2 保持 stream close/error 语义（成功 close 一次；失败 error 一次且不额外 close），并补齐必要测试，verify why.md#scenario-s2-stream-command-execution

## 4. command - CommandProcessor 收敛（降低巨型类耦合点）
- [√] 4.1 抽取单连接协议处理与回写组件 `CommandClientHandler`（text/framed/binary），减少 `CommandProcessor` 巨型方法与耦合点，verify why.md#scenario-s1-sync-command-execution
- [√] 4.2 调整 `CommandProcessor` 调用链以使用抽取组件（保持协议/行为不变），并确保 `CommandProcessor*` 相关测试通过

## 5. Security Check
- [√] 5.1 执行安全检查（输入校验、敏感信息处理、权限控制、confirm token 处理、impact 限流与资源释放），并核对无 EHRB 风险引入

## 6. Documentation Update
- [√] 6.1 同步更新知识库：`helloagents/wiki/modules/command.md`（补充 Pipeline Step/Interceptor 架构与扩展点）
- [-] 6.2 视改动范围更新 `helloagents/wiki/arch.md`（若架构图需要反映新的 Pipeline 结构）

## 7. Testing
- [√] 7.1 运行 `mvn test`，确保现有与新增测试全部通过
