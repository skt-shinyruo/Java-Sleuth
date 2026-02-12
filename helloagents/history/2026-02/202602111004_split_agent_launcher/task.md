# Task List: 拆分 Agent 与 Launcher 产物（降低依赖污染风险）

Directory: `helloagents/plan/202602111004_split_agent_launcher/`

---

## 1. Maven / Packaging
- [√] 1.1 新增 `launcher` Maven module（`launcher/pom.xml` + 根 `pom.xml` modules），verify why.md#独立-launcher-产物可启动并完成-attach #scenario-从源码构建后使用脚本启动并-attach
- [√] 1.2 调整 `core/pom.xml`：产物改为 `java-sleuth-agent`，Manifest 仅保留 `Agent-Class/Premain-Class`，并移除 launcher 侧依赖（JLine），verify why.md#独立-agent-产物可作为--javaagent--attach-注入 #scenario-launcher-通过-attach-api-注入-agent-jar

## 2. Code Split / Shared Protocol
- [√] 2.1 迁移 `core/src/main/java/com/javasleuth/launcher/SleuthLauncher.java` → `launcher/src/main/java/...`，并修正依赖引用，verify why.md#独立-launcher-产物可启动并完成-attach #scenario-从源码构建后使用脚本启动并-attach
- [√] 2.2 迁移 `core/src/main/java/com/javasleuth/command/protocol/*` → `foundation/src/main/java/...`，并确保 agent/launcher 仍可编译，verify why.md#JarLocator-在多-jar-环境下不误选-launcher-jar #scenario-同目录存在两个-fat-jar
- [√] 2.3 增强 `foundation/src/main/java/com/javasleuth/util/JarLocator.java`：按 Manifest 识别 Agent jar（`Agent-Class`/`Premain-Class`），verify why.md#JarLocator-在多-jar-环境下不误选-launcher-jar #scenario-同目录存在两个-fat-jar

## 3. Scripts / Docker / Docs
- [√] 3.1 更新 `sleuth.sh` 与 `sleuth.bat`：优先启动 launcher jar，并通过 `-Dsleuth.agent.jar` 传递 agent jar；保留旧路径回退，verify why.md#独立-launcher-产物可启动并完成-attach #scenario-从源码构建后使用脚本启动并-attach
- [√] 3.2 更新 Docker/demo/perf/security 脚本与 README/docs：替换 `core/target/*-jar-with-dependencies.jar` 的旧假设，verify why.md#独立-launcher-产物可启动并完成-attach #scenario-从源码构建后使用脚本启动并-attach

## 4. Security Check
- [√] 4.1 执行安全检查：启动参数与路径处理（避免任意路径注入误用）、日志中不泄露敏感信息（HMAC secret），按 G9 要求记录发现与修复

## 5. Documentation / Knowledge Base Sync
- [√] 5.1 更新知识库：`helloagents/wiki/arch.md`（新增 ADR-010 索引）、`helloagents/wiki/modules/launcher.md`/`helloagents/wiki/modules/agent.md`（产物拆分与启动方式），并更新 `helloagents/CHANGELOG.md`

## 6. Testing
- [√] 6.1 运行 `mvn test`（或 `mvn -pl ... test`）验证单测通过；必要时补充 `JarLocatorTest` 覆盖多 jar 识别场景
