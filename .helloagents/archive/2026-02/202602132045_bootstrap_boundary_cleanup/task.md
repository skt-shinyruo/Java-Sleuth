# Task List: Bootstrap 边界收敛与重复实现去重

Directory: `helloagents/plan/202602132045_bootstrap_boundary_cleanup/`

---

## 1. Maven / 模块边界
- [√] 1.1 新增 `bootstrap/` Maven 模块并加入根 `pom.xml`，验证 reactor build，verify why.md#requirement-bootstrap-可见性最小化-scenario-agent-attach-后-bootstrap-可见类收敛
- [√] 1.2 将 `monitor/data/snapshot util` 从 `foundation` 迁移到 `bootstrap`（保持包名不变），verify why.md#requirement-bootstrap-可见性最小化-scenario-agent-attach-后-bootstrap-可见类收敛

## 2. Bootstrap 侧配置与监控拦截器
- [√] 2.1 `com.javasleuth.monitor.*` 去除对 `ProductionConfig` 的依赖，改为读取 `sleuth.monitoring.*` sysprop（带默认值），verify why.md#requirement-bootstrap-可见性最小化-scenario-agent-attach-后-bootstrap-可见类收敛
- [√] 2.2 core 启动阶段将关键监控配置同步到 sysprop（未显式覆盖时写入 effective 值），verify why.md#requirement-bootstrap-可见性最小化-scenario-agent-attach-后-bootstrap-可见类收敛

## 3. 去重与 SSOT 收敛
- [√] 3.1 Jar 定位/marker 校验统一：SleuthAgent/SleuthLauncher 改用 `JarLocator` 的公共 API，verify why.md#requirement-关键规则单一来源ssot-scenario-jar-定位与-agentargs-规则统一
- [√] 3.2 `agentArgs` → sysprop 落地统一：提取公共工具类并替换 SleuthAgent/SleuthAgentCore 的重复实现，verify why.md#requirement-关键规则单一来源ssot-scenario-jar-定位与-agentargs-规则统一

## 4. Security Check
- [√] 4.1 Execute security check (per G9: input validation, sensitive info handling, permission control, EHRB risk avoidance)

## 5. Documentation Update
- [√] 5.1 更新知识库：模块分层/构建产物/Bootstrap 暴露面说明（`helloagents/wiki/modules/agent.md` 等）
- [√] 5.2 更新 `helloagents/CHANGELOG.md`

## 6. Testing
- [√] 6.1 `mvn test` 全量回归
