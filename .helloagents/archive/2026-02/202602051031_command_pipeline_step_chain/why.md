# Change Proposal: 命令执行 Pipeline Step/Interceptor 链重构

## Requirement Background
- 当前命令子系统存在典型“巨型类/巨型文件”问题：`CommandProcessor.java`（约 1131 行）、`CommandPipeline.java`（约 616 行）以及部分命令实现（如 `StackCommand.java`、`TtCommand.java`）。
- 现状将输入校验、授权、危险命令二次确认、缓存、impact 限流、超时执行、输出清洗、流式语义（close/error/END marker）等混杂在少数类中，导致：
  - 修改成本高：一个小改动容易触发跨职责回归；
  - 单测粒度难以聚焦：很难只覆盖某个步骤的行为；
  - 扩展困难：新增“策略/规则/观测点”只能继续堆条件分支，类规模进一步膨胀。

目标：在不改变对外协议与命令行为（legacy/framed/binary、streaming 语义、审计与安全边界）的前提下，将 `CommandPipeline` 的执行链路显式化为可插拔步骤链（Step/Interceptor），并为后续拆分 `CommandProcessor` / 超大命令实现提供稳定的中间层。

## Change Content
1. 引入显式的 Pipeline Step/Interceptor 链，将 precheck 与 execution 的关键步骤拆分为独立组件并按顺序编排。
2. `CommandPipeline` 退化为“门面/编排器（Facade）”：对外 API 保持不变，内部委托给步骤链执行。
3. 将异常映射与输出治理（sanitize）从“散落在各处的 try/catch/if”收敛为统一步骤，保证同步与流式两条路径语义一致。
4. 为后续拆分 `CommandProcessor` 与超大命令实现（如 stack/tt）预留一致的扩展点（例如 metrics hook、审计 hook、统一超时/限流策略）。

## Impact Scope
- **Modules:** command, security, monitoring, util, test
- **Files:**（拟变更）
  - `src/main/java/com/javasleuth/command/CommandPipeline.java`
  - `src/main/java/com/javasleuth/command/CommandProcessor.java`（仅限“调用方式/职责下沉”相关的局部收敛）
  - `src/main/java/com/javasleuth/command/*`（新增 `pipeline/` 子包或同级文件，承载 Step/Chain/Invocation 等）
  - `src/test/java/com/javasleuth/command/*`（新增/调整测试以覆盖步骤顺序与关键边界）
- **APIs:** 无对外 API/协议变更（对客户端协议保持兼容）
- **Data:** 无数据模型变更

## Core Scenarios

### Requirement: R1 Pipeline Step Chain
**Module:** command
将命令执行拆为显式步骤链，步骤可单测，组合可回归。

#### Scenario: S1 Sync Command Execution
前置：收到已解析的命令与 `CommandRegistry.Entry`
- 依次执行：输入校验 → 授权 → 危险命令确认 →（可选）缓存 → impact permit → 超时执行 → 输出清洗
- 任一步骤失败均短路返回明确错误消息
- 成功时返回 sanitized 输出

#### Scenario: S2 Stream Command Execution
前置：命令实现 `StreamCommand` 且客户端请求 streaming
- 依次执行：impact permit → 超时执行（executor）→ GuardedStreamSink（按 chunk sanitize）
- 成功：sink 只 close 一次（summary 可为空）
- 失败：sink 只 error 一次（不额外 close，保持现有语义）

### Requirement: R2 Security Boundary Consistency
**Module:** command / security
保持既有安全边界与参数处理语义不变。

#### Scenario: S3 Confirm Token Is Not Part Of Validation/Authz
前置：命令携带 `--confirm <token>` 或 `--confirm=<token>`
- 输入校验与授权检查使用“剥离 confirm 参数后的 args”
- 危险命令确认步骤仍使用原始 args 以完成 token 校验
- 执行阶段使用 confirm 步骤返回的 normalized args（不再包含 confirm token）

### Requirement: R3 High Impact Governance
**Module:** command / security
保持高影响命令的单飞（single-flight）与资源治理。

#### Scenario: S4 High Impact Single-Flight
前置：命令 meta 标记 `impact=HIGH` 且 `security.impact.high.concurrent.limit=1`
- 同一时刻只允许 1 个高影响命令实际执行
- 并发请求返回明确错误，并确保 permit 在所有失败路径下正确释放

### Requirement: R4 Cache Isolation
**Module:** command / util
保持缓存隔离策略，避免跨客户端泄露。

#### Scenario: S5 Cache Key Includes ClientId
前置：命令 meta 可缓存且命令满足安全可缓存条件
- 缓存 key 至少包含 clientId（避免跨 client 命中同一缓存）
- `dashboard realtime` 绕过缓存；`session` 不可缓存

## Risk Assessment
- **Risk:** 步骤链重构导致行为回归（错误消息、超时/限流边界、stream close/error 语义）
  - **Mitigation:** 保持对外 API 不变；优先复用现有实现代码；以现有测试为“契约”并补齐步骤级单测；逐步迁移，保证每个变更点可回滚。
- **Risk:** permit/超时取消路径导致资源泄露或死锁
  - **Mitigation:** 复用现有 permit/timeout 逻辑；新增针对“executor rejection/timeout/interrupted”的释放测试。
- **Risk:** 新增抽象带来性能开销
  - **Mitigation:** 步骤链只做薄封装；避免在热路径上创建过多临时对象；保持现有线程池/队列策略。
