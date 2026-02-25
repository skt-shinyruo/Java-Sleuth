# Change Proposal: 强类型配置模型（Typed Config Models）

## Requirement Background
当前配置/协议层存在“字符串 Key + 多处默认值”的维护风险：

1. **字符串 Key 分散**：核心链路中大量出现 `config.getXxx("some.key", default)` 形式的读取，调用点散落在 `core` / `launcher` / `foundation` 多个模块，导致：
   - 修改/新增配置项时容易漏改（拼写、重命名、兼容逻辑分散）
   - 语义不自解释（同一类配置无法通过类型/字段聚合呈现）
2. **默认值去中心化**：同一配置项的默认值可能同时存在于：
   - `foundation/src/main/resources/sleuth-default.properties`（默认资源）
   - `foundation/src/main/java/com/javasleuth/config/DefaultConfigFallback.java`（资源缺失时的兜底）
   - 代码调用点 `getXxx(key, default)`（运行时兜底）
   这使得默认值容易产生“漂移”（不同位置不一致），并且在安全/协议等关键路径上会放大风险。
3. **协议参数分散计算**：例如文本协议行上限、frame payload 上限等存在“代码中动态计算默认值”的情况（在 server 与 launcher 两侧均出现），一旦计算规则不一致会造成握手/连接行为不一致。

## Change Content
1. 引入强类型配置对象（示例：`ProtocolConfig` / `SecurityConfig` / `ServerConfig`），将“配置 Key、默认值、校验规则、归一化规则”集中在一个解析入口。
2. 在边界处（启动/会话/请求开始）基于 `ConfigSnapshot` 解析一次并校验，核心链路只依赖强类型字段，减少散落的 `String key + default`。
3. 增加“默认值一致性”校验与单测：确保 `sleuth-default.properties`、`DefaultConfigFallback` 与强类型模型的默认值/解析规则保持一致，防止回归漂移。
4. 渐进迁移：保留现有 `ProductionConfig`/`ConfigView` 接口以保证兼容，但禁止新增散落 key；逐步将关键路径迁移到强类型配置对象。

## Impact Scope
- **Modules:** foundation / core / launcher / docs
- **Files:**（预期修改范围）
  - `foundation/src/main/java/com/javasleuth/config/*`
  - `foundation/src/main/java/com/javasleuth/config/model/*`（新增）
  - `core/src/main/java/com/javasleuth/command/server/*`
  - `core/src/main/java/com/javasleuth/command/server/protocol/*`
  - `launcher/src/main/java/com/javasleuth/launcher/*`
  - `core/src/test/java/com/javasleuth/config/*`（新增/补充）
  - `helloagents/wiki/modules/config.md`

## Core Scenarios

### Requirement: 配置读取强类型化与默认值收敛
**Module:** config
核心链路不再直接依赖散落的字符串 key 与默认值。

#### Scenario: 协议相关配置读取（server 与 launcher 一致）
前置：未提供外部配置或仅覆盖部分配置项  
- `ProtocolConfig` 统一产出 `mode/streaming/maxPayload/textMaxLineBytes` 等字段
- server 与 launcher 使用相同的解析/默认/校验规则（避免连接不一致）

### Requirement: 启动/会话级解析一次并校验
**Module:** protocol
在连接建立/握手前解析并校验配置，避免在 IO 循环中重复解析或产生混合状态。

#### Scenario: 建立连接后进入握手与命令处理
前置：新连接建立  
- 在会话开始处创建 `ConfigSnapshot` 并解析为强类型配置对象
- 后续处理只读取强类型字段（减少字符串 key 与散落默认值）

### Requirement: 默认值一致性可自动验证
**Module:** config
默认值/解析规则必须可回归验证，避免“文档/默认资源/代码”三方漂移。

#### Scenario: CI 运行一致性测试
前置：构建或单测执行  
- 若 `sleuth-default.properties` 缺失关键 key 或与强类型默认/解析规则冲突，测试直接失败并给出提示

## Risk Assessment
- **Risk:** 配置解析从“按需动态读取”变为“边界解析一次”可能影响 runtime overrides 的即时生效语义  
  **Mitigation:** 以 `ConfigSnapshot` 为边界（会话/请求级），允许在下一次会话/请求进入时读取到最新 overrides；必要时保留部分动态读取入口
- **Risk:** 默认值对齐可能引入行为变化（尤其是安全默认）  
  **Mitigation:** 明确 SSOT（`sleuth-default.properties`），增加回归测试覆盖关键默认项；对有争议的默认值通过兼容策略/显式配置进行过渡

