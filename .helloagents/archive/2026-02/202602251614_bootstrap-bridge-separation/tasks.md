# 任务清单: bootstrap-bridge-separation

> **@status:** completed | 2026-02-25 16:26

```yaml
@feature: bootstrap-bridge-separation
@created: 2026-02-25
@status: completed
@mode: R3
```

<!-- LIVE_STATUS_BEGIN -->
状态: completed | 进度: 9/9 (100%) | 更新: 2026-02-25 16:28:09
当前: -
<!-- LIVE_STATUS_END -->

## 进度概览

| 完成 | 失败 | 跳过 | 总数 |
|------|------|------|------|
| 9 | 0 | 0 | 9 |

---

## 任务列表

### 1. 构建产物（agent/bootstrap）

- [√] 1.1 在 `agent/pom.xml` 中移除对 `java-sleuth-bootstrap` 的编译期依赖（保证 agent JDK-only）
- [√] 1.2 在 `agent/pom.xml` 中添加 `maven-dependency-plugin`：将 `java-sleuth-bootstrap` jar 复制到 `agent/target/`，命名为 `java-sleuth-bootstrap-bridge-${project.version}.jar`

### 2. 启动链路（agent）

- [√] 2.1 在 `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java` 中改为仅 append bridge jar（不再 append self jar）
- [√] 2.2 在 `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java` 中改为通过 bootstrap classloader 反射调用 `JarLocator` 与 `SystemPropertyRollbackRegistry`（避免编译期依赖与 SSOT 分裂风险）
- [√] 2.3 为 bridge jar 增加最小安全校验：禁止包含 `com/javasleuth/agent/`；并在 append 后验证关键 bridge 类由 BootstrapClassLoader 加载

### 3. 验证

- [√] 3.1 运行 `mvn test`（至少覆盖 `core` 的 JUnit 测试）验证无回归
- [√] 3.2 运行 `mvn -DskipTests package`，确认 `agent/target/java-sleuth-bootstrap-bridge-*.jar` 与 agent jar 均产出

### 4. 知识库同步

- [√] 4.1 更新 `.helloagents/modules/agent.md` 与 `.helloagents/modules/bootstrap.md`：同步“仅 append bridge jar”的边界与产物说明
- [√] 4.2 归档方案包并更新 `.helloagents/CHANGELOG.md`（按 KnowledgeService/PackageService 规范）

---

## 执行日志

| 时间 | 任务 | 状态 | 备注 |
|------|------|------|------|
| 2026-02-25 16:22:07 | 1.1-1.2 | completed | agent 模块改为 JDK-only，并在打包阶段复制 bootstrap bridge jar 到 agent/target |
| 2026-02-25 16:22:07 | 2.1-2.3 | completed | SleuthAgent 仅 append bridge jar，并通过 bootstrap classloader 反射调用 bootstrap 工具类 |
| 2026-02-25 16:23:24 | 3.1 | completed | mvn test 全模块通过 |
| 2026-02-25 16:24:19 | 3.2 | completed | mvn -DskipTests package 成功产出 bridge jar 与 agent jar |
| 2026-02-25 16:25:34 | 4.1 | completed | 已同步更新 agent/bootstrap 模块文档（产物与可见性边界） |
| 2026-02-25 16:28:09 | 4.2 | completed | 方案包已归档，CHANGELOG 已更新并链接至 archive |

---

## 执行备注

> 说明：按规则 DESIGN Phase1/Phase2 需要调用子代理，但当前 Codex 子代理被 HelloAGENTS 规则误触发确认闸门，已按“子代理调用失败→降级主上下文执行”处理；如需恢复子代理流程，可后续单独排查子代理提示词/隔离规则。
