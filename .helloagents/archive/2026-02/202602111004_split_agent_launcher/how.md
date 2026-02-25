# Technical Design: 拆分 Agent 与 Launcher 产物（降低依赖污染风险）

## Technical Solution

### Core Technologies
- Java 8
- Maven multi-module
- `maven-assembly-plugin`（`jar-with-dependencies` fat-jar）
- Attach API（`com.sun.tools.attach`）

### Implementation Key Points
1. **新增 launcher 模块**：创建 `launcher/` Maven module，产出 `java-sleuth-launcher-*-jar-with-dependencies.jar`
2. **core 模块收敛为 agent**：`core/` 仅负责 agent 侧运行时（command/agent/enhancement/monitor/...），产出 `java-sleuth-agent-*-jar-with-dependencies.jar`
3. **抽取协议到 foundation**：将 `com.javasleuth.command.protocol` 迁移到 `foundation`，供 agent 与 launcher 共享
4. **JarLocator 增强**：从“按后缀匹配 jar”升级为“按 Manifest 识别 Agent jar”，避免在多 jar 场景下误选 launcher jar
5. **脚本与文档更新**：
   - `sleuth.sh/.bat` 优先运行 launcher jar，并通过 `-Dsleuth.agent.jar=<path>` 明确传递 agent jar
   - Docker/demo/perf/security 脚本按新产物命名定位

## Architecture Decision ADR

### ADR-010: 拆分 Agent 与 Launcher 为独立产物
**Context:** 当前同一 fat-jar 同时承载 launcher 与 agent，并把多种三方依赖原样注入目标 JVM，存在依赖碰撞与定位歧义风险。  
**Decision:** 拆分为 `java-sleuth-agent` 与 `java-sleuth-launcher` 两个产物，并把协议层下沉到 `foundation` 作为共享依赖。  
**Rationale:**  
- 将 launcher 侧依赖（尤其是 JLine）从目标 JVM 运行时剥离，降低碰撞面  
- 让启动链路更明确：launcher 负责 attach，agent 负责目标 JVM 内服务  
- 为后续进一步隔离（shade/relocate 或独立类加载器）提供清晰边界  
**Alternatives:**  
- 方案 A：继续单 jar fat-jar → 拒绝原因：依赖碰撞面最大、定位歧义难消除  
- 方案 B：shade+relocate/隔离类加载器 → 拒绝原因：改动更大、需要更强的兼容验证；作为下一阶段可选优化  
**Impact:**  
- 构建产物与脚本路径变更；需要同步更新文档与 CI/脚本  
- `JarLocator` 行为增强（更鲁棒但涉及 jar 读取）

## Security and Performance
- **Security:**  
  - launcher 通过 `-Dsleuth.agent.jar` 或 `SLEUTH_AGENT_JAR` 显式指定 agent jar，避免扫描误选导致 attach 到错误 jar  
  - JarLocator 读取 Manifest 时仅做本地文件 IO，不引入网络访问
- **Performance:**  
  - agent jar 依赖面缩小（移除 launcher 依赖），减少目标 JVM 类解析与冲突概率  
  - JarLocator 扫描目录时应控制扫描范围与次数（优先 override，其次同目录/常见目录）

## Testing and Deployment
- **Testing:**  
  - `mvn clean test` 验证全量单测通过  
  - 增补/调整 JarLocator 单测（多 jar 识别场景）
- **Deployment:**  
  - 发布目录建议同时放置 `java-sleuth-launcher-*-jar-with-dependencies.jar` 与 `java-sleuth-agent-*-jar-with-dependencies.jar`  
  - `sleuth.sh/.bat` 作为默认入口（可兼容 Java 8 tools.jar 场景）

