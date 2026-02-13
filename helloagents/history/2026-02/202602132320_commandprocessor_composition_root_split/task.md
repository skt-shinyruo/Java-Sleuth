# Task List：CommandProcessor composition root 拆分与去中心化

Directory: `helloagents/plan/202602132320_commandprocessor_composition_root_split/`

---

## 1. core/command.session（会话映射封装）
- [√] 1.1 新增 `ClientSessionIndex`（封装 clientId→sessionId 映射）到 `core/src/main/java/com/javasleuth/command/session/ClientSessionIndex.java`，verify why.md#req-session-index
- [√] 1.2 将 `core/src/main/java/com/javasleuth/command/server/CommandClientHandler.java` 从直接依赖 `ConcurrentHashMap<String,String>` 改为依赖 `ClientSessionIndex`，verify why.md#req-session-index

## 2. core/command（组件包 + factory 装配）
- [√] 2.1 新增 `CommandProcessorComponents`（聚合 config/security/metrics/audit/executor/registry/pipeline/bootstrapper/acceptor/handler/coordinator 等）到 `core/src/main/java/com/javasleuth/command/CommandProcessorComponents.java`，verify why.md#req-assembly-split
- [√] 2.2 新增 `CommandProcessorFactory`（默认装配 + 可注入装配）到 `core/src/main/java/com/javasleuth/command/CommandProcessorFactory.java`，verify why.md#req-assembly-split

## 3. core/command（CommandProcessor 门面纯化）
- [√] 3.1 重构 `core/src/main/java/com/javasleuth/command/CommandProcessor.java`：构造函数仅接收 components/依赖，默认构造路径委派 factory；移除或外移全局副作用（日志 provider / JobManager config）到 composition root，verify why.md#req-facade-boundary
- [√] 3.2 保持对外 API 行为一致：`start/shutdown/shutdownGracefully/emergencyShutdown/restart/addShutdownHook/getShutdownStatus` 等语义不变，verify why.md#req-facade-boundary

## 4. core/agent.core（composition root 调整）
- [√] 4.1 调整 `core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java`：使用 `CommandProcessorFactory` 进行装配（或显式构造 components），确保单例获取与全局配置收敛到 agent core，verify why.md#req-assembly-split

## 5. Tests（回归基座）
- [√] 5.1 调整 `core/src/test/java/com/javasleuth/command/CommandProcessor*Test*.java`：构造方式迁移到 factory/注入构造，锁定现有行为与边界（过载拒绝/安全边界/队列容量），verify why.md#req-facade-boundary
- [√] 5.2 调整 `launcher/src/test/java/com/javasleuth/launcher/client/ProtocolClientIntegrationTest.java`：确保集成闭环仍可启动 processor 并走握手+命令执行路径，verify why.md#req-facade-boundary

## 6. Security Check
- [√] 6.1 执行安全检查（G9）：确认未引入 secret 明文日志、权限绕过、输入校验缺失；确认 factory/组件装配不改变安全模式边界

## 7. Documentation Update（知识库同步）
- [√] 7.1 更新 `helloagents/wiki/modules/command.md`：补充 factory/components/session index 边界说明与调用入口，保持 SSOT 一致
- [√] 7.2 更新 `helloagents/CHANGELOG.md`：记录本次 refactor 变更点

## 8. Testing
- [√] 8.1 执行 `mvn test`（或最小相关模块测试），确保全部测试通过；必要时补充单测覆盖 `ClientSessionIndex` 边界
