# Change Proposal: 显式构造注入（Security Managers）+ Launcher Signer 小接口

## Requirement Background

当前工程已经在多个关键点引入了“可注入依赖 + shutdown 收口”，但仍存在两类影响可演进性与可测试性的隐式耦合：

1. **安全管理器仍以单例为主（隐式依赖）**
   - `RequestSecurityManager#getInstance()`、`AuthorizationManager#getInstance()` 等在核心路径被直接调用，会把配置、审计、缓存等状态以“全局静态”的形式隐藏起来。
   - 虽然已有 `shutdownInstance()` 降低 detach→re-attach 的残留风险，但单例仍使得：
     - 单测隔离困难（难以在同 JVM 内构造多个互不影响的实例）
     - 未来做多实例隔离/嵌入式库形态成本更高

2. **Launcher 侧 signer 仍偏“具体实现耦合”**
   - Launcher/客户端只需要“签名封装”能力，但当前直接依赖 `RequestSecurityManager` 这一大对象（同时包含服务端验签/防重放缓存逻辑）。
   - 若未来要做 Web UI/headless client 或把签名策略替换为不同实现（例如外部密钥服务、不同协议），缺少一个足够小的抽象接口作为稳定边界。

本次变更目标：在不破坏现有 API 的前提下，继续把“安全组件”和“客户端 signer”从单例/具体类依赖中解耦出来，形成可注入、可替换、可测试的边界，同时保留 `getInstance()` 兼容路径。

## Change Content
1. **引入 Signer 小接口（foundation）**
   - 新增 `CommandSigner`：只定义客户端需要的签名方法（最小接口）。
   - `ProtocolClient` 依赖 `CommandSigner` 而不是具体的 `RequestSecurityManager`，便于 headless/Web UI 复用。

2. **Security Managers 支持显式构造注入（foundation）**
   - `RequestSecurityManager` / `AuthorizationManager` 增加公开构造函数（允许传入 config/audit/auth 等依赖），内部不强制拉取单例。
   - 保留 `getInstance()` 作为兼容入口（仍可用、行为不变）。

3. **Composition Root 收敛单例获取（core）**
   - 在 `SleuthAgentCore` 中优先通过显式构造创建 security managers（并注入共享依赖），再传入 `CommandProcessor`。
   - 仍保留 shutdown 路径对 `shutdownInstance()` 的 best-effort 清理（兼容仍有旧路径调用单例的代码）。

## Impact Scope
- **Modules:**
  - `foundation`（新增 signer 接口；security managers 构造注入）
  - `launcher`（ProtocolClient 依赖抽象 signer）
  - `core`（SleuthAgentCore composition root 收敛单例）
- **Files:** 以 task.md 列表为准
- **APIs:** 对外协议与 CLI 不变；仅新增可注入构造/接口（向后兼容）
- **Data:** 无

## Core Scenarios

### Requirement: 客户端 signer 可替换（Web UI/headless 复用）
**Module:** foundation/launcher
客户端侧只依赖 `CommandSigner`，可在不引入完整安全管理器的情况下完成签名封装。

#### Scenario: ProtocolClient 使用自定义 signer
- 前置：实现一个自定义 `CommandSigner`
- 执行：创建 `ProtocolClient` 并执行命令
- 期望：签名行为由注入实现决定；默认实现保持不变

### Requirement: security managers 可显式构造（降低单例隐式依赖）
**Module:** foundation/core
在 `SleuthAgentCore` 中通过构造注入创建 `RequestSecurityManager`/`AuthorizationManager`，并确保行为与 `getInstance()` 一致。

#### Scenario: 同 JVM 内可创建多个独立 manager 实例（测试隔离）
- 前置：构造两个 manager 实例（共享或不共享 config/audit）
- 执行：分别处理请求
- 期望：缓存/限流/nonce 等状态不相互污染

## Risk Assessment
- **Risk:** 新增构造函数后，调用方可能绕过 `shutdownInstance()` 语义导致资源未关闭
  - **Mitigation:** 仍保持 shutdown 编排对“注入实例”调用 `shutdown()`；`shutdownInstance()` 仅作为兼容/兜底；在文档中明确生命周期归属。
- **Risk:** ProtocolClient 改为依赖接口可能导致测试/构造路径调整
  - **Mitigation:** 保留默认 connect 方法与兼容重载；单测覆盖关键路径。

