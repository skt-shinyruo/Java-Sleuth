# Technical Design: Container 模块化 + 依赖边界硬化

## Technical Solution

### Core Technologies

- Java 8（保持现有兼容目标）
- Maven 多模块（模块边界作为第一层编译期约束）
- JUnit 4（沿用现有测试体系）
- Maven Enforcer（构建期依赖守护，避免分层回退）

### Implementation Key Points

1. **新增顶层装配模块 `container`**
   - 产物目标：提供“可被 bootstrap agent 直接加载”的运行时 jar（包含 core 及其外部依赖）
   - 职责：composition root、对象装配、生命周期编排、detach/reattach 清理、全局副作用收口
2. **core 退化为纯库**
   - core 不再包含 `Factory`/装配器/单例初始化；只保留运行时逻辑、协议处理与可测试组件
   - 禁止 core 依赖 container（通过构建期守护确保）
3. **四大 God Class 的拆分方向（类型化边界）**
   - `ProductionConfig`：Facade（对外保留读取入口）+ Typed Config/Snapshot + Runtime Overrides Store + Masker/Validator/Loader/Persister
   - `AuthenticationManager`：Authenticator + SessionStore + AttemptTracker + CleanupScheduler（由 container 启停）
   - `CommandRegistry`：Registry + Registrar + ConflictResolutionPolicy；Provider/插件加载移至 container（或至少拆为 Loader）
   - `CommandProcessorFactory`：从 core 迁出；在 container 内实现 `CommandProcessorComponents` 的装配（Assembler/Builder）
4. **模块级约束（编译期守护）**
   - 依赖图约束：`bootstrap`/`foundation`（JDK only）→ `core` → `container`
   - 入口约束：仅 container 持有“创建线程池/调度器/单例/插件 classloader”的能力，其他模块按需依赖窄接口
5. **破坏性变更策略（允许 breaking，但可控）**
   - 删除/收敛默认构造器与 `getInstance()` 的隐式回退路径
   - 对外保留少量 bridge（如需要），但把调用点收口到 container，避免扩散

## Architecture Design

```mermaid
flowchart TD
    subgraph L0[bootstrap/agent (thin, JDK-only)]
        A[SleuthAgent bootstrap] -->|locate container jar| B[isolated ClassLoader]
    end

    subgraph L3[container (composition root)]
        C[SleuthRuntimeContainer] --> C1[Config bootstrap]
        C --> C2[Security services]
        C --> C3[Command runtime wiring]
        C --> C4[Lifecycle/Shutdown]
        C3 --> P[CommandProviderLoader + PluginVerifier]
    end

    subgraph L2[core (runtime library)]
        R[CommandRegistry (pure)] -->|register| M[CommandMeta/Entry]
        CP[CommandProcessor (facade)] --> Srv[server/* lifecycle]
        CP --> Pipe[CommandPipeline]
    end

    subgraph L1[foundation (JDK-only)]
        F1[ProductionConfig/ConfigView] --> FC[Typed Config Parser/Snapshot]
        F2[Authentication services] --> FS[SessionStore + CleanupScheduler]
        F3[AuditLogger/InputValidator]
        PV[PluginJarVerifier (allowlist+sha256)]
    end

    B --> C
    C --> R
    C --> CP
    C --> F1
    C --> F2
    C --> F3
    P --> PV
```

## Architecture Decision ADR

### ADR-001: 引入 `container` 作为唯一 composition root
**Context:** core 中存在 `CommandProcessorFactory`、`SleuthAgentCore` 等装配入口，导致装配与业务逻辑混杂、依赖形态不透明、难以约束边界。  
**Decision:** 新增 Maven 模块 `container`，将装配/生命周期编排迁入该模块；bootstrap agent 直接加载 container 产物作为运行时入口。  
**Rationale:** 以 Maven 模块硬边界阻断“低层反向依赖高层”，并把副作用集中到单处，降低回归复杂度。  
**Alternatives:** 继续在 core 内拆分类但不引入模块边界 → Rejection reason: 仍然缺少编译期约束，边界会逐渐回到“靠约定”。  
**Impact:** 构建产物与入口加载路径会变化（breaking change），需同步更新 JarLocator/启动文档与集成测试。

### ADR-002: 以显式生命周期替代隐式单例与自启动后台线程
**Context:** `AuthenticationManager` 等在构造时自启动清理任务，并通过 `getInstance()` 扩散，导致 detach→reattach 状态残留与测试污染。  
**Decision:** 将后台任务/调度器从业务对象中剥离，变为可显式 start/stop 的组件；container 统一编排其生命周期。  
**Rationale:** 生命周期可测试、可回收、可幂等；并减少“构造即副作用”导致的边界外溢。  
**Alternatives:** 保持单例但增加 shutdownInstance 调用点 → Rejection reason: 调用点不可控且容易遗漏，仍然靠约定。  
**Impact:** 需要更新创建路径与若干调用点；可能删除默认构造与 `getInstance()` 入口。

### ADR-003: 插件加载从 registry 中剥离，并以 Policy/Verifier 类型化
**Context:** `CommandRegistry` 同时承担 provider 发现、插件目录扫描、allowlist/sha256 校验、classloader 生命周期与命令冲突策略。  
**Decision:** 将插件加载与校验迁入 container（或独立 Loader），并引入显式策略对象（AllowlistPolicy/ConflictResolutionPolicy/PluginJarVerifier）。  
**Rationale:** registry 回归“注册表”的单一职责；安全策略可集中审计与测试；classloader 生命周期可回收。  
**Alternatives:** 仅在 `CommandRegistry` 内部私有拆方法 → Rejection reason: 仍然是同一个模块内的隐式耦合，边界不清晰。  
**Impact:** `CommandRegistry` 构造器与装配方式将调整（breaking change），需要同步改造 `BuiltinCommandProvider` 注入链路。

## Security and Performance

- **Security:**
  - 插件加载必须显式启用且强制 allowlist+sha256（拒绝即审计）
  - 认证/会话清理任务停止与缓存清理纳入 detach 编排，避免跨 attach 残留
  - 禁止在非回环绑定下以 `security.mode=off` 启动（沿用现有策略），并确保容器启动前完成配置校验
- **Performance:**
  - 线程池与队列参数统一在容器边界处解析（Typed Config），避免散落读取与默认值漂移
  - 插件 classloader 显式关闭，避免 Metaspace/FD 泄漏

## Testing and Deployment

- **Testing:**
  - 单测：Policy/Verifier/AttemptTracker/SessionStore 等纯逻辑覆盖
  - 集成：启动 command server → 连接握手 → 执行基础命令（help/status/auth）→ detach → re-attach 回归
  - 插件：plugins.enabled=false 不加载；enabled 且 allowlist/sha256 不匹配应拒绝并审计
- **Deployment:**
  - 新增 container 产物后，bootstrap agent 的 jar 定位策略需要同步更新（或提供 fallback）
  - 文档/示例脚本更新：`sleuth.sh`/Docker 示例若依赖旧 jar 名称，需要一并调整
