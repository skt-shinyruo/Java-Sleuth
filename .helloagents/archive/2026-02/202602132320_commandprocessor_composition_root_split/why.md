# 变更提案：CommandProcessor 去中心化与装配边界拆分

## 需求背景

`CommandProcessor` 当前更接近一个 **facade + composition root（组合根）** 的混合体：

- 虽然 accept loop / 协议处理 / shutdown 编排已拆到 `ServerBootstrapper`、`ConnectionAcceptor`、`CommandClientHandler`、`ShutdownCoordinator` 等组件，但 `CommandProcessor` 仍然在构造阶段集中完成大量依赖装配与全局副作用（日志/JobManager 配置、线程池、security/metrics/audit 单例获取与注入）。
- `sessionByClient` 仍作为共享可变状态被注入多处（handler/执行器链路），导致“会话映射”边界穿透，横切关注点（安全/审计/指标/线程池）依赖扩散。

这会带来以下问题：

1. **耦合中心仍强**：修改任何横切点（如安全模式、线程模型、metrics 维度）都可能波及 `CommandProcessor` 的装配逻辑。
2. **可测试性受限**：默认构造路径依赖多个 `getInstance()` 单例与全局配置副作用，导致单测更难做到“只测编排/只测边界”。
3. **职责边界不清**：对外是 lifecycle 门面，但内部仍承担“装配 + 状态汇聚”的责任，容易再次回到巨型对象演化路径。

## 变更内容

1. 将 `CommandProcessor` 的 **依赖装配与全局副作用** 收敛到专用 Factory/Builder（composition root），`CommandProcessor` 保持为“薄门面”。
2. 将 `sessionByClient` 从裸 `ConcurrentHashMap` 抽象为 `ClientSessionIndex`（或等价命名），明确会话映射边界，降低共享状态穿透。
3. 用“组件包（Components）”聚合横切依赖（config/security/metrics/audit/executor/registry/pipeline），减少构造函数与字段扇出。
4. 补齐回归基座：确保 lifecycle 行为、协议握手、权限边界、过载拒绝与 shutdown 幂等在重构后保持一致。

## 影响范围

- **Modules：**
  - `core`：`command` / `command.server` / `command.session`
  - `core`：`agent.core`（composition root 调整）
  - `launcher`：可能涉及集成测试构造方式
- **Files（预估）：**
  - `core/src/main/java/com/javasleuth/command/CommandProcessor.java`
  - `core/src/main/java/com/javasleuth/command/server/CommandClientHandler.java`
  - `core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java`
  - `core/src/test/java/com/javasleuth/command/*CommandProcessor*Test*.java`
  - `launcher/src/test/java/.../ProtocolClientIntegrationTest.java`
  - 新增：`core/src/main/java/com/javasleuth/command/*Factory.java`、`.../*Components.java`、`.../session/*Index.java`
- **APIs：**
  - 目标：保持 `CommandProcessor` 对外入口（`start/shutdown/restart/...`）稳定
  - 允许：新增 factory/builder API；默认构造方式可标注为“仅兼容/测试用途”
- **Data：** 无

## 核心场景

<a name="req-facade-boundary"></a>
### Requirement: facade_boundary（CommandProcessor 仅保留门面职责）
**Module:** core/command

#### Scenario: start_stop_restart_semantics
在不改变协议与行为语义的前提下：
- `start()` 仍能完成 bind → accept-loop（委派）并处理启动失败路径
- `shutdownGracefully()/emergencyShutdown()` 行为与资源释放顺序保持一致
- `restart()` 语义保持一致（先 shutdown，再延迟 start）

<a name="req-assembly-split"></a>
### Requirement: assembly_split（装配从 CommandProcessor 拆出）
**Module:** core/agent.core + core/command

#### Scenario: test_constructibility
- 单测可以用注入方式构造命令服务（无需依赖默认单例路径）
- 依赖注入与默认装配路径的行为一致（配置/安全/metrics/audit）

<a name="req-session-index"></a>
### Requirement: session_index_encapsulation（会话映射封装）
**Module:** core/command.session

#### Scenario: connect_disconnect_cleanup
- 连接建立后能正确绑定 clientId → sessionId
- 断连时能正确解绑并触发 logout/close 清理

## 风险评估

- **风险：行为回归（协议/权限/metrics）**
  - **Mitigation：** 保持协议 handler 与 pipeline 不改语义；优先“搬迁装配/抽象状态”，不改业务逻辑；补齐/强化单测与轻量集成测试。
- **风险：构造路径变更影响外部调用**
  - **Mitigation：** 保留现有构造器与方法签名；新增 factory 并逐步迁移调用点；对默认构造路径做兼容委派。
- **风险：shutdown 编排依赖扩散**
  - **Mitigation：** 只做“依赖聚合/注入方式”调整，不改变 `ShutdownCoordinator` 的资源释放顺序；必要时为其引入更窄接口在后续迭代解决。

