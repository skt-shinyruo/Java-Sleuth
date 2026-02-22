# Change Proposal: Container 模块化 + 四大 God Class 职责收敛

## Requirement Background

当前 `ProductionConfig` / `AuthenticationManager` / `CommandRegistry` / `CommandProcessorFactory` 承担了过多职责（配置加载+校验+运行时覆写、认证+会话+清理、插件加载+allowlist/sha256 校验+冲突策略+注册、线程池/组件编排等），导致：

1. **边界主要靠约定而不是类型/模块**：大量 `getInstance()`/静态状态与隐式副作用，使依赖关系只能“读代码猜”，编译期无法约束。
2. **测试与演进成本上升**：装配逻辑与业务逻辑混在一起，难以替换依赖、难以做场景化回归，容易出现跨模块的偶然耦合。
3. **attach/detach 生命周期风险**：后台线程、classloader、会话/缓存状态如果收口不彻底，会导致同 JVM 二次 attach 出现残留与非确定性行为。

本变更允许 breaking change，并以“一次性整体收敛”为目标：通过引入运行时容器/装配模块（container），把装配与生命周期编排从 core/foundation 的业务类中剥离出来，同时强化 Maven 模块依赖约束与架构守护。

## Change Content

1. **新增 `container` Maven 模块**：作为顶层运行时容器与 composition root，集中负责装配、生命周期编排与资源回收（线程池/插件 classloader/会话清理/metrics 等）。
2. **core 退化为纯库**：把装配入口从 `CommandProcessorFactory` 等类迁出；core 仅保留命令执行/协议/服务端逻辑与可测试的纯逻辑组件。
3. **拆分 `CommandRegistry`**：将“命令注册表”与“provider/插件加载”“allowlist+sha256 校验”“冲突策略”解耦为显式类型（Policy/Loader/Verifier/Registrar）。
4. **拆分 `AuthenticationManager`**：将“凭证校验”“会话状态”“失败尝试/锁定策略”“过期清理调度”解耦，并把启动/停止清理任务的生命周期收口到 container。
5. **继续收敛 `ProductionConfig`**：将校验/默认/派生规则与运行时覆写边界显式化（Typed Config/Snapshot/Store），并逐步禁止业务对象内部直接调用 `ProductionConfig.getInstance()`。
6. **模块级约束与守护**：在构建期加入依赖约束（禁止反向依赖、禁止跨层引用），并提供最小化的架构回归手段（防止“靠约定”的边界再次松动）。

## Impact Scope

- **Modules:**
  - `container`（新增）
  - `core`（移除/下沉装配、拆分 registry/factory）
  - `foundation`（收敛 config/security 单例与职责）
  - `agent`（bootstrap 侧加载目标 jar/entrypoint 的逻辑可能调整）
  - `launcher`（测试与集成路径可能需要适配）
  - `bootstrap`（仅作为桥接能力，保持不反向依赖）
- **Files:** 预计涉及多文件重排（新增模块 + 移动/替换部分入口类），具体以 task.md 为准
- **APIs:**
  - 可能移除/降级若干 `getInstance()` 与默认构造路径
  - `CommandProcessor` 的“自举构造器”可能被移除，仅保留显式注入 `CommandProcessorComponents`
  - Agent core entrypoint / jar 定位策略可能调整（breaking change）
- **Data:** 无数据库变更；涉及配置项与默认值语义需保持 SSOT（以配置文档与默认配置为准）

## Core Scenarios

### Requirement: Module Boundary Hardening
**Module:** build / parent
以 Maven 模块作为第一层硬边界，阻断低层模块反向依赖高层模块，减少“编译能过、运行靠约定”的情况。

#### Scenario: Maven build-time guard
在 CI/本地构建时：
- `foundation` / `bootstrap` 保持 dependency-free（JDK only），禁止引入非必要依赖
- `core` 不能依赖 `container`
- `container` 可以依赖 `core` / `foundation` / `bootstrap`，作为顶层装配模块
- 违反规则应在构建期失败（而不是运行时报错）

### Requirement: Runtime Container as Composition Root
**Module:** container
所有装配与全局副作用（线程池、单例生命周期、后台任务、插件 classloader）集中在 container，core 不再承担装配职责。

#### Scenario: Attach-Run-Detach idempotent
同一 JVM 内多次 attach/detach：
- detach 时必须 best-effort 关闭线程池/清理任务/插件 classloader
- detach 后不遗留认证会话与 nonce/confirm 缓存等安全状态
- 再次 attach 行为可预测（不依赖前一次残留状态）

### Requirement: Config Boundary Type Narrowing
**Module:** config / container
配置读取在边界处一次性解析/校验/归一化（Typed Config / Snapshot），核心逻辑依赖窄接口或强类型对象。

#### Scenario: Request-level config snapshot
一次命令执行链路内：
- 读取配置使用一致性快照（避免读到混合状态）
- 运行时 overrides 可观测（来源可解释、审计可追溯）

### Requirement: AuthN Session Lifecycle Isolation
**Module:** security / container
认证/会话/清理的生命周期由容器统一编排，避免 `AuthenticationManager` 自启动后台线程导致边界外溢。

#### Scenario: Session cleanup stops on detach
detach/shutdown 时：
- 会话清理调度器停止（幂等）
- session/attempt 状态可清空或随 runtime 一起释放

### Requirement: Command Plugin Loading Boundary
**Module:** command / container / security
插件加载属于装配与供应链边界，应与 registry 解耦并显式策略化。

#### Scenario: Plugins disabled by default + allowlist sha256
当 `plugins.enabled=false`：
- 不扫描插件目录
- 不启用 classpath ServiceLoader provider（需显式开关）

当 `plugins.enabled=true`：
- 插件 jar 必须通过 allowlist+sha256 校验
- 校验失败：拒绝加载并写入审计日志
- 插件 classloader 生命周期可控，detach 时关闭释放

## Risk Assessment

- **Risk:** 入口类/构建产物变化导致启动路径不兼容  
  **Mitigation:** 提供过渡期 fallback（如同时支持旧 core jar 与新 container jar 的定位/加载），并补充启动/集成测试
- **Risk:** 生命周期回收不完整导致线程/classloader 泄漏  
  **Mitigation:** container 统一 close() 编排；为关键资源添加单测/集成回归（detach→reattach）
- **Risk:** 插件策略与冲突策略语义漂移  
  **Mitigation:** 将策略显式类型化（Policy），并为 allowlist/sha256/冲突策略编写可读的行为测试
