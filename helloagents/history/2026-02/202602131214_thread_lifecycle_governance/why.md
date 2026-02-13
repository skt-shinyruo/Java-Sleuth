# Change Proposal: 线程与生命周期治理（Threading & Lifecycle Governance）

## Requirement Background

当前工程在多个核心组件中直接创建线程/线程池（`new Thread(...)`、`ThreadPoolExecutor`、`ScheduledExecutorService` 等），并由各自组件分散管理生命周期。

这会带来以下长期成本与风险：

1. **线程策略不一致**：线程命名/daemon/priority/异常处理（UncaughtExceptionHandler）分散在各处，难以形成统一可观测性与排障习惯。
2. **shutdown 收敛不完整**：命令服务端的 graceful shutdown 已有编排（`ShutdownCoordinator`），但仍存在部分后台线程/定时任务未纳入统一关闭路径（例如会话清理线程）。
3. **上下文传播与泄漏风险**：虽然命令执行链路已对 `CommandContextHolder`/`SleuthLogContext` 做了 try/finally 清理，但背景任务（如 JobManager 后台任务）仍可能出现“上下文丢失/跨任务残留”的隐性问题，影响日志关联与安全审计准确性。

本次变更目标不是“禁止线程”，而是把“线程/任务生命周期”提升为可集中治理的一等概念，并把治理逻辑固化到统一的、可测试的代码结构中。

## Change Content

1. 引入统一的线程创建与关闭工具（ThreadFactory/Executor shutdown helper），为后续治理提供稳定入口。
2. 将 `AuthenticationManager` 的会话清理后台任务纳入可控生命周期（可 shutdown、可重启），并接入统一 shutdown 编排。
3. 补齐后台 Job 执行的上下文传播与生命周期收敛（可 shutdown executor），避免 stop/detach 后残留线程池与任务。

## Impact Scope

- **Modules:**
  - `foundation`：线程工具、`AuthenticationManager`、（可选）Memory/Performance 优化器的生命周期收敛入口
  - `core`：shutdown 编排增强、JobManager 生命周期与上下文传播
- **Files (expected):**
  - `foundation/src/main/java/com/javasleuth/util/*`
  - `foundation/src/main/java/com/javasleuth/security/AuthenticationManager.java`
  - `core/src/main/java/com/javasleuth/command/server/ShutdownCoordinator.java`
  - `core/src/main/java/com/javasleuth/command/JobManager.java`
  - `core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java`
  - `core/src/test/java/com/javasleuth/security/*`（补充/更新单测）
- **APIs:** 无对外协议变更（主要为内部生命周期与可维护性提升）
- **Data:** 无

## Core Scenarios

### Requirement: shutdown 后不残留后台任务
**Module:** core/foundation
Stop/detach 或命令服务端 shutdown 后，后台任务应停止（或进入可预测的 idle 状态），避免长期运行线程留在目标 JVM 中。

#### Scenario: stop 命令触发 shutdown 后，会话清理任务停止
前置：Agent 已 attach，存在 `sleuth-session-cleanup` 类后台任务  
- stop/detach 后不再持续执行清理循环
- 再次 attach 时可重新启动清理任务（不依赖进程重启）

### Requirement: 线程创建策略统一（命名/daemon/异常处理）
**Module:** foundation
统一线程工厂（ThreadFactory）与 executor shutdown 辅助方法，减少每处重复实现与策略漂移。

#### Scenario: 新增后台 executor 时不再复制粘贴线程工厂
前置：需要创建 `ScheduledExecutorService` 或 `ThreadPoolExecutor`  
- 统一通过工具类创建（或至少复用统一 ThreadFactory）
- 线程命名规则与 daemon 策略一致
- 未捕获异常能进入统一日志（便于定位）

### Requirement: 后台 Job 具备上下文传播与清理
**Module:** core
JobManager 的后台执行应捕获触发命令的上下文（clientId/sessionId/connId/command 等），并在后台线程中执行前写入、结束后清理，避免线程池复用时上下文残留。

#### Scenario: trace/monitor 等 --bg 任务日志可关联 clientId/sessionId
前置：命令通过 `--bg` 提交后台任务  
- Job 内部日志包含稳定的关联字段
- Job 结束/取消后清理 ThreadLocal，上下文不串号

## Risk Assessment

- **Risk:** shutdown/重启语义变化可能影响“同 JVM 反复 attach/detach”的边界行为  
  **Mitigation:** 所有可 shutdown 的单例服务支持 restart（必要时重置 instance），并通过单测覆盖“shutdown → getInstance → 可继续使用”的最小闭环。
- **Risk:** 引入统一线程工具可能导致少量线程名变化，影响排障脚本/习惯  
  **Mitigation:** 优先保持现有线程名不变（或仅增加后缀），并在状态/文档中说明。
