# Change Proposal: 修复 watch --no-params/--no-return 语义与插桩行为不一致

## Requirement Background

当前 `watch` 的帮助文案将 `--no-params/--no-return` 定义为“不要采集参数/返回值”，而不是“不要发送 METHOD_ENTRY/METHOD_EXIT 事件”。但现有 ASM 插桩把“是否发送事件”与“是否采集字段”绑定，导致：

1. `watch ... --no-params` 可能完全看不到 `METHOD_ENTRY`（看起来像没触发）。
2. `watch ... --no-return` 可能完全看不到正常返回的 `METHOD_EXIT`（只剩异常事件）。

这属于语义偏离（功能缺陷），会误导用户排障。

## Change Content

1. **事件发送与字段采集解耦**：无论是否采集参数/返回值，都应发送 entry/exit 事件。
2. **数据模型增强**：为 `WatchResult` 增加“是否采集”标记（如 `parametersCaptured/returnCaptured`），用于输出层明确展示 `<not captured>`，避免与真实 `null` 混淆。
3. **输出层语义对齐**：当用户通过 `--expr` 选择 `params/return` 字段时，如果对应值未采集，输出明确的占位提示，而不是静默缺失或误显示为 `null`。

## Impact Scope

- **Modules:**
  - core（ASM 插桩、命令输出）
  - bootstrap（拦截器、数据模型）
- **Files（预期）:**
  - `core/src/main/java/com/javasleuth/enhancement/WatchEnhancer.java`
  - `bootstrap/src/main/java/com/javasleuth/monitor/WatchInterceptor.java`
  - `bootstrap/src/main/java/com/javasleuth/data/WatchResult.java`
  - `core/src/main/java/com/javasleuth/command/impl/WatchCommand.java`
  - `core/src/test/java/...`（新增/更新单测）
  - `README.md`（补充语义说明，若需要）
  - `helloagents/wiki/modules/monitor.md`（知识库同步，若存在对应章节）

## Core Scenarios

### Requirement: watch 语义一致性修复（no-params/no-return）
**Module:** enhancement / monitor / command
修复 `watch --no-params/--no-return` 的语义偏离，使其只影响“值采集”，不影响“事件发出”。

#### Scenario: `--no-params` 仍产生 METHOD_ENTRY
前置条件：
- 执行 `watch <class> <method> --no-params`

预期结果：
- 仍产生 `METHOD_ENTRY` 事件
- `params` 字段显示为 `<not captured>`（或等价占位），而不是缺失/不输出

#### Scenario: `--no-return` 仍产生正常 METHOD_EXIT
前置条件：
- 执行 `watch <class> <method> --no-return`

预期结果：
- 正常返回时仍产生 `METHOD_EXIT` 事件
- `return` 字段显示为 `<not captured>`（或等价占位），而不是缺失/只剩异常事件

## Risk Assessment

- **Risk:** 输出格式变化可能影响部分用户的日志/脚本解析（尤其是 `--expr` 输出）。
- **Mitigation:** 保持默认行为（不带 `--no-*`）输出尽量不变；仅在未采集时新增明确占位输出，并通过单测覆盖关键场景。

