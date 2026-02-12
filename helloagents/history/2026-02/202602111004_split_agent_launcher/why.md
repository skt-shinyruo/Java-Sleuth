# Change Proposal: 拆分 Agent 与 Launcher 产物（降低依赖污染风险）

## Requirement Background
当前 `core` 模块使用 `maven-assembly-plugin` 产出 `*-jar-with-dependencies.jar`，并在同一个 fat-jar 的 Manifest 中同时声明 `Main-Class` 与 `Agent-Class/Premain-Class`。这会导致：

1. **职责混杂**：Launcher（CLI/Attach/交互）与 Agent（目标 JVM 内运行的诊断服务）被打包为同一产物。
2. **依赖污染风险**：fat-jar 会把 ASM/Jackson/JLine/CFR 等三方库以“原包名”打进 Agent；当 Agent 注入目标 JVM 后，与业务应用已有依赖发生版本碰撞的概率较高（典型表现为 `NoSuchMethodError`/`LinkageError`/行为差异）。
3. **定位歧义**：`JarLocator` 目前按 `*-jar-with-dependencies.jar` 后缀定位 agent jar，在拆分产物或多 jar 场景下可能误选错误 jar。

## Change Content
1. **拆分为两个独立产物**：
   - `java-sleuth-agent`：仅包含 Agent 入口与目标 JVM 内运行所需的实现与依赖。
   - `java-sleuth-launcher`：仅包含 CLI/Attach/交互会话与依赖。
2. **抽取共享协议代码**：将 `com.javasleuth.command.protocol` 从 agent 侧抽离到 `foundation`，使 launcher 不再依赖 agent 模块。
3. **构建与启动链路更新**：更新 `sleuth.sh/.bat`、Docker/demo 脚本与文档，确保：
   - 运行 launcher 时可稳定定位到 agent jar（优先 `-Dsleuth.agent.jar` / 环境变量覆盖，其次基于 Manifest 识别）。

## Impact Scope
- **Modules:** `core(->agent)` / `launcher(new)` / `foundation(shared)`
- **Files:** Maven `pom.xml`、脚本、少量 Java 文件移动（launcher 与 protocol）、`JarLocator` 行为增强、文档与知识库同步
- **APIs:** 无新增对外 API；启动方式与产物路径发生变化
- **Data:** 无

## Core Scenarios

### Requirement: 独立 Launcher 产物可启动并完成 attach
**Module:** launcher
Launcher 产物独立运行，不应携带 Agent 入口属性；应在 attach 时加载 agent jar。

#### Scenario: 从源码构建后使用脚本启动并 attach
前置：执行 `mvn clean package`  
- `sleuth.sh/.bat` 能定位 launcher jar 与 agent jar
- Launcher 启动后可选择 JVM 并成功 attach

### Requirement: 独立 Agent 产物可作为 -javaagent / attach 注入
**Module:** agent(core)
Agent 产物只包含 `Agent-Class/Premain-Class` 入口属性，避免 Main-Class 混入导致定位歧义。

#### Scenario: Launcher 通过 Attach API 注入 agent jar
前置：Launcher 已获取 agent jar 真实路径  
- `VirtualMachine.loadAgent(agentJar, args)` 成功
- 目标 JVM 内 `SleuthAgent.agentmain/premain` 正常启动命令服务

### Requirement: JarLocator 在多 jar 环境下不误选 launcher jar
**Module:** foundation(util)
`JarLocator.locateAgentJar(...)` 在同目录存在 launcher/agent 两个 `*-jar-with-dependencies.jar` 时，应优先识别 Manifest 中包含 `Agent-Class`/`Premain-Class` 的 jar。

#### Scenario: 同目录存在两个 fat-jar
前置：launcher 与 agent jar 位于同一目录  
- JarLocator 不返回 launcher jar
- JarLocator 返回包含 `Agent-Class` 或 `Premain-Class` 的 agent jar

## Risk Assessment
- **Risk:** 构建模块拆分导致脚本/文档/历史路径失效  
  **Mitigation:** 脚本保留旧路径回退逻辑；文档同步更新并提供 override 参数（`-Dsleuth.agent.jar` / `SLEUTH_AGENT_JAR`）
- **Risk:** 共享协议包迁移导致编译失败或类重复  
  **Mitigation:** 明确仅迁移 `com.javasleuth.command.protocol`；通过 `mvn test` 验证
- **Risk:** 依赖碰撞风险仍未彻底消除（ASM/Jackson 等仍在 agent 内）  
  **Mitigation:** 作为阶段性降险；后续可在 ADR 里保留 shade/relocate 或隔离类加载器作为下一步方案

