# Change Proposal: 统一命令执行与流式治理管道重构（Solution 2）

## Requirement Background
当前 Java-Sleuth 已具备较完整的 JVM 诊断、方法观测与热更新能力，但在“多 ClassLoader 生产形态 + 长时间流式观测 + 安全边界/插件治理”组合场景下存在可预期性与稳定性缺口，表现为：

1. 多 ClassLoader 下 `watch/trace/redefine` 目标类选择与回滚不可靠：启动时取第一个匹配类，停止时又仅凭 `className` 二次扫描回滚，可能选错同名类导致残留增强或重定义错对象。
2. ASM 插桩使用 `COMPUTE_FRAMES` 在复杂依赖/自定义 ClassLoader 下更易失败；当前失败策略会直接移除该类的 enhancers，造成“静默永久失效”。
3. 流式命令（`watch/trace/tt/monitor/stack`）绕过 `CommandPipeline` 的超时/隔离/输出治理，连接线程被长时间占用，输出缺少统一脱敏与截断策略，legacy 文本模式存在结束边界脆弱性。
4. `--bg` 后台任务每次提交都新起线程，缺少全局运行中并发上限，容易在误用下拖垮目标 JVM。
5. 安全/配置/插件/文件编码治理语义不一致：plugins 默认关闭但仍可能通过 `ServiceLoader` 加载 classpath provider；`config save` 不包含 runtime overrides；`mc` 编码不固定；`redefine` 文件路径校验不统一。

本方案选择“统一执行与流式重构（Solution 2）”，目标是通过一条统一的执行/输出管道，把上述问题在架构层一次性收敛与治理，减少后续继续堆补丁带来的复杂度与漂移风险。

## Change Content
1. 引入统一的“目标类解析/选择”机制：支持多匹配时显式选择 ClassLoader，并保证回滚/重定义绑定到同一个 `Class<?>` 实例或可验证的 loader 标识。
2. 将 `watch/trace/tt/monitor/stack` 纳入统一的 `CommandPipeline` 治理：统一执行线程池、超时、并发许可（impact permit）、输出脱敏/截断、以及更可靠的结束标记。
3. 重构后台作业模型：后台任务进入统一 executor + 全局运行中并发上限 + 背压/拒绝策略，避免无限线程膨胀。
4. 提升 ASM 插桩成功率与可恢复性：实现 loader-aware `ClassWriter`（`getCommonSuperClass` 走目标 loader），失败策略从“移除 enhancers”升级为“可回退/可重试的失败策略（带冷却/计数/可观测）”。
5. 统一插件、配置持久化、文件/编码与安全校验语义：默认不加载 classpath `ServiceLoader` provider（除非显式开启）；`config save` 支持持久化 runtime overrides；`mc` 默认 UTF-8/可选编码；`redefine` 走统一文件路径校验。

## Impact Scope
- **Modules:**
  - command（CommandProcessor/CommandPipeline/命令实现）
  - enhancement（SleuthClassFileTransformer/ASM writer）
  - security（输出治理、输入校验补强、插件加载策略）
  - config（新增/调整配置项与默认值）
  - util（类匹配/loader 标识、输出治理辅助）
  - monitor（流式输出路径对接，确保断连清理一致）
- **Files（预计）：**
  - `src/main/java/com/javasleuth/command/impl/WatchCommand.java`
  - `src/main/java/com/javasleuth/command/impl/TraceCommand.java`
  - `src/main/java/com/javasleuth/command/impl/TtCommand.java`
  - `src/main/java/com/javasleuth/command/impl/MonitorCommand.java`
  - `src/main/java/com/javasleuth/command/impl/StackCommand.java`
  - `src/main/java/com/javasleuth/command/impl/RedefineCommand.java`
  - `src/main/java/com/javasleuth/command/CommandProcessor.java`
  - `src/main/java/com/javasleuth/command/CommandPipeline.java`
  - `src/main/java/com/javasleuth/command/JobManager.java`
  - `src/main/java/com/javasleuth/command/CommandRegistry.java`
  - `src/main/java/com/javasleuth/enhancement/SleuthClassFileTransformer.java`
  - `src/main/java/com/javasleuth/config/ProductionConfig.java`
  - `src/main/java/com/javasleuth/command/impl/ConfigCommand.java`
  - `src/main/java/com/javasleuth/command/impl/MemoryCompilerCommand.java`
  - `src/main/resources/sleuth-default.properties`
  - `config-templates/production-sleuth.properties`
  - `docs/ops/operations-runbook.md` / `docs/ops/production-deployment-guide.md`
  - `helloagents/wiki/modules/*.md`（按需同步）
- **APIs（命令/配置）：**
  - 命令新增可选参数：`--loader` / `--loader-hash` / `--classloader`（以最终设计为准）
  - 新增/调整配置项：`plugins.serviceloader.enabled`、`jobs.max.running`、`enhancement.failure.*`、`protocol.legacy.end.marker.enabled`（示例）
- **Data:** 不新增持久化数据结构；仅新增运行态统计/计数（metrics/health 可观测项）

## Core Scenarios

### Requirement: 多 ClassLoader 目标选择与回滚可靠性 <a id="req-multiclassloader-target"></a>
**Module:** command / util
解决 `watch/trace/redefine` 在多 ClassLoader 场景下选错目标类/回滚不干净的问题。

#### Scenario: `watch/trace` 明确选择目标 loader 并可稳定回滚 <a id="scn-watch-trace-select-loader"></a>
- 现状：同名类存在多个 loader 时命令命中不确定，停止时可能找错类导致残留增强。
- 期望：
  - 多匹配时返回候选列表（包含 loader 标识），要求显式选择或使用兼容参数选择；
  - 会话记录中绑定到目标 `Class<?>` 或“类名+loaderId”，stop 时只对该目标回滚；
  - 断连/超时/取消时也能触发同样清理。

#### Scenario: `redefine` 在多 loader 同名类时避免误重定义 <a id="scn-redefine-select-loader"></a>
- 期望：
  - 多匹配时拒绝执行并提示候选，要求指定 loader；
  - 指定 loader 后只对该 loader 下的 class 执行 redefine。

### Requirement: ASM 插桩失败可恢复（避免“静默永久失效”） <a id="req-asm-failure-recoverable"></a>
**Module:** enhancement

#### Scenario: `COMPUTE_FRAMES` 失败时仍可观测与可重试 <a id="scn-asm-compute-frames-fallback"></a>
- 期望：
  - 使用 loader-aware `ClassWriter` 降低失败概率；
  - 失败后不直接清空 enhancers；改为记录失败、进入冷却期/按策略降级；
  - 通过 `status/metrics/health` 可以看到失败计数与当前策略状态。

### Requirement: 流式命令进入统一 Pipeline 治理 <a id="req-stream-in-pipeline"></a>
**Module:** command / security

#### Scenario: 流式命令具备统一超时/隔离/输出治理 <a id="scn-stream-guardrails"></a>
- 期望：
  - 流式命令执行也走统一 executor/timeout/impact permit；
  - 对每个输出 chunk 做统一脱敏/截断与字节上限控制；
  - legacy 模式输出边界更可靠（可配置 END marker）。

### Requirement: 后台任务具备并发上限与背压 <a id="req-jobs-concurrency"></a>
**Module:** command

#### Scenario: 多个 `--bg` 任务不会无限起线程/拖垮目标 JVM <a id="scn-jobs-backpressure"></a>
- 期望：
  - 后台任务使用有界 executor；
  - 运行中任务数有全局上限（超过则拒绝并返回明确错误）；
  - 可观测当前 running/queued，支持 stop/stopAll 的一致清理。

### Requirement: 插件/配置/文件编码与安全校验语义一致 <a id="req-consistency"></a>
**Module:** command / config / security

#### Scenario: 默认“插件禁用”时不加载 classpath provider <a id="scn-plugin-serviceloader"></a>
- 期望：`plugins.enabled=false` 时不通过 `ServiceLoader` 自动加载第三方 `CommandProvider`（除非显式开启新开关）。

#### Scenario: `config save` 可选择持久化 runtime overrides <a id="scn-config-save-runtime"></a>
- 期望：提供明确语义（例如 `config save --include-runtime`），并避免把敏感配置打印到 stdout。

#### Scenario: `mc/redefine` 文件与编码规则一致 <a id="scn-file-encoding-policy"></a>
- 期望：默认 UTF-8（或可选编码），且读写路径走统一校验（`SecurityValidator`）。

## Risk Assessment
- **Risk:** 大范围重构导致回归风险上升（协议/命令输出边界、stream 行为、脚本兼容）。  
  **Mitigation:** 通过配置开关与命令兼容参数分阶段上线；新增回归脚本与关键单测；保持默认行为尽量不破坏旧客户端。
- **Risk:** 多 ClassLoader “严格选择”可能影响现有使用习惯。  
  **Mitigation:** 提供兼容模式（例如 `--first` 或 `--strict=false`），并在输出中明确提示风险。
- **Risk:** 输出治理（脱敏/截断）可能降低排障信息量。  
  **Mitigation:** 提供可配置上限与 debug 开关；对敏感字段始终脱敏，对非敏感字段允许提升上限。

