# Change Proposal: 恢复分层边界（Maven 多模块化 + ArchUnit 守护）

## Requirement Background
当前 `core` 代码在包分层上出现多处“低层反向依赖高层”的情况，形成依赖环并持续放大维护成本：

- `util` / `config` / `security` 等低层包反向依赖 `command` / `agent`（典型：`SleuthLogger` 读取 `CommandContext`；`AuthorizationManager` 依赖 `CommandMeta`；`StopCommand` 直接调用 `SleuthAgent`）。
- 结果：包级别天然形成环（且难以靠局部修补解决），导致后续重构“牵一发而动全身”，单测也被迫大量 Mock 或绕过真实实现。

本变更的目标不是“把所有包都拆成独立模块”，而是以**最小可行的 Maven 多模块边界**先把“低层 → 高层”的方向锁死，并用 ArchUnit 在构建期对分层进行守护，避免未来再次回滑。

## Change Content
1. **引入 Maven 多模块边界（foundation/runtime）**：将低层基础能力迁移到 `foundation` 模块（`util/config/security/data`），保持 `runtime`（现 `core`）模块依赖 `foundation`，并保证 `foundation` 不再依赖 `runtime`。
2. **消除关键依赖环的根因**：
   - `security` 不再依赖 `command`：将 `CommandMeta` 等“授权/风险元信息”下沉到 `foundation`（靠近 `security`），`runtime` 仅依赖该模型。
   - `util` 不再依赖 `command`：`SleuthLogger` 去掉对 `CommandContextHolder` 的编译期依赖，日志上下文统一从 `SleuthLogContext`（ThreadLocal）获取，由 `command` 在执行链路中写入。
   - `command` 不再依赖 `agent`：`stop` 命令通过注入的生命周期回调触发 shutdown（而不是直接 import `SleuthAgent`）。
3. **引入 ArchUnit 作为分层守护**：在测试阶段检测并阻止核心分层违规（例如：禁止 `command -> agent`，禁止顶层包之间形成循环依赖等）。
4. **保持打包与启动体验**：继续产出可用的 `-jar-with-dependencies.jar`（Launcher/Agent 入口不变），并确保 `JarLocator` 能在模块化后仍可定位到 agent jar。

## Impact Scope
- **Modules:** `foundation`（新） / `core`（调整为 runtime，依赖 foundation）
- **Files:** 根 `pom.xml`、`foundation/pom.xml`、`core/pom.xml`、若干 Java 源文件迁移与少量解耦重构、ArchUnit 测试新增
- **APIs:** 对外命令协议与 CLI 行为保持不变；仅内部类归属与依赖关系调整
- **Data:** N/A

## Core Scenarios

### Requirement: Foundation Module Boundary
**Module:** foundation / build
以 Maven 模块边界确保低层不再反向依赖高层，形成“不可编译”的硬约束。

#### Scenario: Forbidden imports fail the build
前置：执行 `mvn test`  
- `foundation` 中任何对 `com.javasleuth.command.*` / `com.javasleuth.agent.*` 等 runtime 包的 import 会在编译期直接失败
- ArchUnit 在 `core` 侧额外兜底检测并给出可读错误信息

### Requirement: Security Does Not Depend On Command
**Module:** security / command
授权与危险命令治理的 SSOT 放在低层（靠近 `security`），避免相互依赖。

#### Scenario: CommandMeta lives in foundation
前置：`mvn test`  
- `AuthorizationManager` / `DangerousCommandConfirmationManager` 引用的 `CommandMeta` 来自 `foundation`（不从 `command` 引入）
- `core`（runtime）依赖 `foundation` 获取 `CommandMeta`，无反向依赖

### Requirement: Command Stop Without Agent Dependency
**Module:** command / agent
停止命令不应直接依赖 agent 实现类，避免形成 `command -> agent` 的上层环。

#### Scenario: StopCommand uses injected lifecycle
前置：执行 `stop`  
- `stop` 通过注入的 `LifecycleController`/`ShutdownHook` 触发 shutdown
- `command` 包不再出现对 `com.javasleuth.agent.*` 的 import

### Requirement: Util Logger Does Not Import Command Context
**Module:** util / command
日志是基础设施，应避免依赖命令执行上下文实现细节。

#### Scenario: Context via SleuthLogContext only
前置：命令执行链路运行  
- `command` 在执行前写入 `SleuthLogContext`（包含 commandName、session/conn 等关联字段）
- `SleuthLogger` 仅读取 `SleuthLogContext`，不再编译期依赖 `CommandContextHolder`

### Requirement: Packaging Compatibility
**Module:** launcher / util
模块化后仍需保持“构建产物可定位、可 attach、可交互”。

#### Scenario: Launcher locates agent jar after refactor
前置：`mvn package` 后运行 `sleuth.sh` / `SleuthLauncher`  
- `JarLocator.locateAgentJar(...)` 仍能定位到最新 `*-jar-with-dependencies.jar`
- Agent-Class / Premain-Class / Main-Class manifest entries 不变

## Risk Assessment
- **Risk:** Maven 多模块引入会带来依赖迁移与打包路径变化，短期可能导致构建失败或遗漏依赖。  
  **Mitigation:** 采用“先拆 foundation 再逐步消环”的顺序；每一步都以 `mvn test` / `mvn package` 回归；必要时先做最小模块拆分，再演进更细粒度模块。
- **Risk:** 日志上下文与 stop/shutdown 解耦可能产生行为偏差（例如上下文字段缺失、shutdown 时序变化）。  
  **Mitigation:** 为关键路径补充单测/集成测试；保持默认行为一致（仅替换依赖方式）。

