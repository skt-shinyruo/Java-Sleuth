# 任务清单：Agent 依赖隔离（两段式 Agent + 隔离 ClassLoader）

Directory: `helloagents/history/2026-02/202602111756_agent_classloader_isolation/`

---

## 1. Maven 与产物拆分（Bootstrap / Core）
- [√] 1.1 新增 `agent/` Maven 模块：产出 `java-sleuth-agent-*-jar-with-dependencies.jar`（Manifest 仅 `Agent-Class/Premain-Class`），verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等-scenario-attach-注入不污染目标依赖
- [√] 1.2 调整根 `pom.xml` modules 顺序：加入 `agent`，确保构建顺序正确，verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等
- [√] 1.3 调整 `core/pom.xml`：artifactId 改为 `java-sleuth-agent-core`，移除 `Agent-Class/Premain-Class`，增加 `Sleuth-Agent-Core: true`，verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等

## 2. spy/bridge 下沉与依赖瘦身
- [√] 2.1 将 `core/src/main/java/com/javasleuth/monitor/*` 迁移到 `foundation/src/main/java/com/javasleuth/monitor/*`，确保插桩回调稳定可见，verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等-scenario-attach-注入不污染目标依赖
- [√] 2.2 调整 `foundation/pom.xml`：移除 Jackson/CFR 等三方依赖，避免 bootstrap jar 夹带三方库污染目标 JVM，verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等
- [√] 2.3 重构 `foundation` 中依赖 Jackson/CFR 的实现（例如 `AuditLogger`/`CfrDecompiler`）：
  - `AuditLogger` 改为无第三方依赖的 JSON 输出（或降级为纯文本 + 简易 JSON 生成）
  - `CfrDecompiler` 迁移到 `core`（或仅在 core ClassLoader 内可见）
  verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等

## 3. Bootstrap Agent 实现
- [√] 3.1 新增 `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`：实现 appendToBootstrap + isolated URLClassLoader 加载 core，verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等-scenario--javaagent-启动仍可用
- [√] 3.2 在 `core` 新增 `com.javasleuth.agent.core.SleuthAgentCore` 并迁移原 `SleuthAgent` 逻辑（addTransformer + command server），verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等

## 4. Launcher / JarLocator 联动
- [√] 4.1 扩展 `foundation/src/main/java/com/javasleuth/util/JarLocator.java`：新增 `locateAgentCoreJar(...)`（基于 Manifest `Sleuth-Agent-Core` + 常见目录扫描），verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等-scenario-attach-注入不污染目标依赖
- [√] 4.2 修改 `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`：Attach 时注入 `coreJar=<path>` 到 agentArgs，并在缺失 core jar 时给出明确提示，verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等-scenario-attach-注入不污染目标依赖

## 5. 脚本与文档同步
- [√] 5.1 更新 `sleuth.sh`、`sleuth.bat`：同时定位 bootstrap 与 core jar，设置 `-Dsleuth.agent.jar` / `-Dsleuth.agent.core.jar`，verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等
- [√] 5.2 更新 `docker/*` 与 `docs/ops/*`：部署包需要同时包含 bootstrap 与 core jar（与 launcher 同目录），verify why.md#requirement-彻底隔离-agent-三方依赖asmjacksoncfr-等
- [√] 5.3 同步知识库：更新 `helloagents/wiki/arch.md`、`helloagents/wiki/modules/agent.md`、`helloagents/wiki/modules/launcher.md` 与 `helloagents/CHANGELOG.md`，verify why.md#impact-scope

## 6. Security Check
- [√] 6.1 执行安全检查：确认不会把三方依赖暴露给业务 ClassLoader；确认 core jar 路径解析不会读写敏感路径；确认失败行为不影响业务线程语义（best-effort），per G9

## 7. Testing
- [√] 7.1 `mvn test`：验证单测通过（含 JarLocator/打包/基础行为），verify how.md#testing-and-deployment
- [√] 7.2 `mvn -DskipTests package`：验证产物与 Manifest（bootstrap 有 Agent-Class，core 有 Sleuth-Agent-Core 且无 Agent-Class），verify how.md#testing-and-deployment
