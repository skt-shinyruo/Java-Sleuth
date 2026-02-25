# Task List: 线程与生命周期治理（Threading & Lifecycle Governance）

Directory: `helloagents/plan/202602131214_thread_lifecycle_governance/`

---

## 1. foundation（线程工具 + 安全/会话后台任务）
- [√] 1.1 引入统一线程/Executor 工具类（ThreadFactory + shutdown helper），verify why.md#线程创建策略统一（命名daemon异常处理）-新增后台-executor-时不再复制粘贴线程工厂
- [√] 1.2 重构 `AuthenticationManager` 的会话清理任务为可 shutdown/可重启，并提供幂等 `shutdown()`，verify why.md#shutdown-后不残留后台任务-stop-命令触发-shutdown-后会话清理任务停止

## 2. core（shutdown 编排 + JobManager 生命周期与上下文）
- [√] 2.1 `ShutdownCoordinator` 纳入 `AuthenticationManager` 的 shutdown（graceful/emergency），verify why.md#shutdown-后不残留后台任务-stop-命令触发-shutdown-后会话清理任务停止, depends on task 1.2
- [√] 2.2 `JobManager` 增加 `shutdown(reason)` 并在 agent shutdown 路径调用，同时为后台 job 增加上下文捕获/清理包装，verify why.md#后台-job-具备上下文传播与清理-tracemonitor-等---bg-任务日志可关联-clientidsessionid, depends on task 1.1

## 3. Security Check
- [√] 3.1 执行线程/生命周期相关安全检查：daemon 线程不阻塞 JVM 退出、shutdown 超时可控、ThreadLocal 清理无遗漏、避免新增敏感信息日志

## 4. Documentation Update
- [√] 4.1 更新知识库：补充 thread/lifecycle 治理约定与 shutdown 编排说明（`helloagents/wiki/modules/security.md`、`helloagents/wiki/modules/util.md` 或新增模块文档）
- [√] 4.2 更新 `helloagents/CHANGELOG.md`：记录本次线程与生命周期治理变更

## 5. Testing
- [√] 5.1 补充/更新单测覆盖 `AuthenticationManager.shutdown()` 幂等与可重启
- [√] 5.2 `mvn test` 验证通过
