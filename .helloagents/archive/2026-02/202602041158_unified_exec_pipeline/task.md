# Task List: 统一命令执行与流式治理管道重构（Solution 2）

Directory: `helloagents/plan/202602041158_unified_exec_pipeline/`

---

## 1. 多 ClassLoader 目标选择与回滚（`watch/trace/redefine`）
- [√] 1.1 新增已加载类解析/选择工具（输出候选 + loader 标识），用于多匹配场景处理；验证 why.md#req-multiclassloader-target、why.md#scn-watch-trace-select-loader
- [√] 1.2 改造 `watch`：支持 `--loader/--loader-hash`（或等价参数），session 绑定目标 `Class<?>` 并用其回滚；验证 why.md#req-multiclassloader-target、why.md#scn-watch-trace-select-loader，depends on 1.1
- [√] 1.3 改造 `trace`：同 `watch` 的目标选择与回滚绑定；验证 why.md#req-multiclassloader-target、why.md#scn-watch-trace-select-loader，depends on 1.1
- [√] 1.4 改造 `redefine`：多候选时拒绝并提示，支持显式选择 loader；同时补齐文件路径读取前校验；验证 why.md#scn-redefine-select-loader，depends on 1.1

## 2. ASM 插桩可靠性与失败可恢复策略
- [√] 2.1 实现 loader-aware `ClassWriter`（重写 `getCommonSuperClass`），并在 `SleuthClassFileTransformer` 中使用；验证 why.md#req-asm-failure-recoverable、why.md#scn-asm-compute-frames-fallback
- [√] 2.2 设计并实现插桩失败策略（冷却/可重试/可观测），替换“失败即移除 enhancers”的逻辑；验证 why.md#req-asm-failure-recoverable，depends on 2.1
- [√] 2.3 将失败计数与策略状态接入 `status/metrics/health`（至少一种可观测路径）；验证 why.md#req-asm-failure-recoverable，depends on 2.2

## 3. 流式命令纳入统一 Pipeline（超时/隔离/输出治理）
- [√] 3.1 扩展 `CommandPipeline`：支持 `StreamCommand` 走统一 executor/timeout/impact permit，并对每个输出 chunk 执行 `sanitize/truncate`；验证 why.md#req-stream-in-pipeline、why.md#scn-stream-guardrails
- [√] 3.2 改造 `CommandProcessor`（framed/legacy）：流式命令不再在 client 线程直接执行；输出统一 END marker（可配置）；验证 why.md#req-stream-in-pipeline、why.md#scn-stream-guardrails，depends on 3.1
- [√] 3.3 改造 binary 路径：同样接入统一 Pipeline 与输出治理；验证 why.md#req-stream-in-pipeline、why.md#scn-stream-guardrails，depends on 3.1

## 4. 后台 Job 并发上限与背压
- [√] 4.1 改造 `JobManager`：使用有界 executor，新增 `jobs.max.running` 与队列上限；提交超限返回明确错误；验证 why.md#req-jobs-concurrency、why.md#scn-jobs-backpressure
- [√] 4.2 统一 `--bg` 命令在资源不足时的提示与审计（watch/trace/tt/monitor/stack）；验证 why.md#req-jobs-concurrency，depends on 4.1

## 5. 插件/配置/文件编码与安全校验一致性
- [√] 5.1 调整插件加载策略：`plugins.enabled=false` 时不加载 classpath `ServiceLoader` provider；新增显式开关（例如 `plugins.serviceloader.enabled`）；验证 why.md#req-consistency、why.md#scn-plugin-serviceloader
- [√] 5.2 `config save` 支持持久化 runtime overrides（例如 `--include-runtime`），并避免敏感信息落 stdout；验证 why.md#scn-config-save-runtime
- [√] 5.3 `mc` 默认 UTF-8（或支持 `--encoding`），并补齐输入校验/错误提示；`redefine` 文件校验统一化；验证 why.md#scn-file-encoding-policy

## 6. Security Check
- [√] 6.1 执行安全检查（输入校验、输出脱敏、权限控制、危险命令二次确认、插件加载边界、文件路径校验），确保不引入新的 EHRB 风险

## 7. Documentation Update
- [√] 7.1 更新知识库模块文档：`helloagents/wiki/modules/command.md`、`helloagents/wiki/modules/enhancement.md`、`helloagents/wiki/modules/security.md`、`helloagents/wiki/modules/config.md`（按实际改动选择）
- [√] 7.2 更新运维文档与配置模板：`docs/ops/*`、`config-templates/production-sleuth.properties`、`src/main/resources/sleuth-default.properties`

## 8. Testing
- [√] 8.1 增加/更新 JUnit4 单测：多 loader 选择器、插桩失败策略、Pipeline 对流式输出的治理（至少覆盖 sanitize/timeout/END marker 行为）
- [-] 8.2 运行脚本回归：`scripts/test/test-all-commands.sh`（需要本地启动 agent/端口 3658 环境），覆盖三种协议与 `--bg` 并发上限
