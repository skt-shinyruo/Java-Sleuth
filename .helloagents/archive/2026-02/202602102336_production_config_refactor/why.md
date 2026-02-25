# Change Proposal: 降低 ProductionConfig 架构吸力（拆分职责 + 明确运行时可变语义）

## Requirement Background
当前 `ProductionConfig` 以“全局单例 + 可变运行时覆盖”的形态存在，并逐步演化为配置相关能力的集中入口（God Config）：

- 单文件承载职责过多：加载/默认值/系统属性覆盖/运行时 overrides/保存/敏感字段脱敏/日志路径与 PID 等。
- 代码规模偏大：`foundation/src/main/java/com/javasleuth/config/ProductionConfig.java` 约 680 行、方法约 88 个，且 `ProductionConfig.getInstance()` 调用点约 30+。

结构性风险：
1. **隐式依赖**：任何地方都能直接拿配置并读取/写入，调用链不透明，排查“为什么变了 / 从哪变的”很困难。
2. **并发与生命周期语义混杂**：同一个入口既像启动快照（load+defaults），又支持运行时可变（runtime overrides），缺少边界与可观测性，容易引入竞态与不可预期行为。

## Change Content
1. **引入清晰配置边界**：拆分“只读视图”与“运行时可写覆盖”，并提供来源可观测性（origin/source）。
2. **拆分 ProductionConfig 职责**：加载/保存/脱敏/日志路径/PID 等下沉为独立类；`ProductionConfig` 退化为组合与门面（Facade）。
3. **逐步降低全局单例吸力**：边界处（Agent/Launcher/CommandProcessor）集中创建并传递 `ConfigView`；核心组件按需依赖窄接口，避免随手 `getInstance()`。
4. **请求级一致性（可选增强）**：在命令执行入口捕获 `ConfigSnapshot`，保证同一次命令链路读取一致。

## Impact Scope
- **Modules:** foundation(config/security/util), core(command/launcher/agent)
- **Files:** 新增多个 config 组件类；调整少量调用点；补齐配置优先级/运行时覆盖/脱敏/保存等测试
- **APIs:** 以内部 API 为主；对外命令与配置键保持兼容（如需启用“禁用键校验”，需单独开关与兼容说明）
- **Data:** N/A

## Core Scenarios

### Requirement: Config Semantics
**Module:** config
明确配置读取优先级与“一次请求内一致性”的语义边界。

#### Scenario: Priority Is Explainable
前置：同时存在 file/system/runtime 三种来源  
- 读取优先级明确且稳定：`runtime overrides > system properties > file/default`
- 可查询 key 的 origin/source，用于定位“为什么变了”

#### Scenario: Request Snapshot Consistency (Optional)
前置：命令开始执行  
- 在入口捕获 `ConfigSnapshot`
- 命令执行期间读取只从 snapshot 获取，避免中途 overrides 造成同一请求内读到混合状态

### Requirement: Runtime Overrides Are Traceable
**Module:** config / command
运行时覆盖写入必须可追溯，避免“谁改了/什么时候改了”不可定位。

#### Scenario: Config Set Is Audited
前置：执行 `config set <k> <v>`  
- 记录 key/old/new/source/ts（敏感值脱敏）
- 可在 status/debug 输出中定位最近变更

### Requirement: God Config Decomposition
**Module:** config
收敛职责，避免 `ProductionConfig` 成为吸力中心。

#### Scenario: ProductionConfig Becomes Facade
前置：构建通过  
- 生产配置加载/保存/脱敏/路径等通过独立类实现
- `ProductionConfig` 单类复杂度下降，职责聚焦为“组合与读取门面”

## Risk Assessment
- **Risk:** 行为漂移（默认值/读取优先级/保存语义）导致兼容问题  
  **Mitigation:** 增加优先级与持久化单测；分阶段迁移，保持旧 API 兼容并提供回滚路径
- **Risk:** 安全边界被运行时覆盖绕过（例如 `security.mode`、HMAC secret 等）  
  **Mitigation:** 将可运行时修改的 key 做 allowlist；对安全关键项增加审计与限制（必要时要求额外确认）
