# Change Proposal: Maven 多模块化（core + examples）

## Requirement Background
当前仓库采用单 Maven 模块结构，主工程与示例应用源码同仓库维护，但示例应用（`examples/`）需要通过脚本单独 `javac` 编译到独立目录（`target/examples-classes`）以避免进入发布 fat-jar。该方式可用，但在依赖管理、IDE 导入、构建一致性与扩展性方面不如标准 Maven 多模块结构清晰。

目标是将仓库改造为标准 Maven 多模块：
- 根工程作为 **parent/aggregator**（`packaging=pom`），只负责聚合与统一配置；
- 主产物（agent/launcher fat-jar）落在 `core/` 模块；
- 示例应用落在 `examples/` 模块；
- 同步更新脚本、Docker 与文档，确保 `mvn clean package` 与演示流程稳定可用。

## Change Content
1. 根工程改为 parent/aggregator：新增 `<modules>`，统一 Java/编码等基础属性
2. 新增 `core/` Maven 模块并迁移现有主工程源码与构建插件配置
3. 将 `examples/` 补齐为 Maven 模块（`examples/pom.xml`），由 Maven 编译示例源码
4. 更新脚本与 Docker 构建逻辑：从 `core/target/` 定位 fat-jar，并确保 demo/perf/security 脚本仍能运行示例 JVM
5. 更新用户文档与知识库：构建产物路径与结构说明保持一致

## Impact Scope
- **Modules:** launcher / agent / command / security / util / test(examples)
- **Files:**
  - Maven：`pom.xml`、`core/pom.xml`、`examples/pom.xml`
  - 目录迁移：`src/` → `core/src/`
  - 脚本：`sleuth.sh`、`sleuth.bat`、`scripts/*`、`docker/demo/*`
  - 文档：`README.md`、`docs/*`、`helloagents/wiki/*`、`helloagents/CHANGELOG.md`
- **APIs:** 无外部 API 变更（构建/运行入口与路径变更）
- **Data:** 无数据结构变更

## Core Scenarios

### Requirement: 多模块构建与产物边界清晰
**Module:** build
将主产物与示例应用隔离，避免示例类进入发布 jar/fat-jar。

#### Scenario: 根目录执行构建
前置：安装 JDK 8+ 与 Maven  
- 执行 `mvn clean package` 可成功构建全部模块
- 主 fat-jar 产出位于 `core/target/*-jar-with-dependencies.jar`
- 示例应用由 `examples` 模块编译产出（不进入主 fat-jar）

### Requirement: Demo/测试脚本可用
**Module:** scripts / docker
脚本与 Docker demo 仍可一键启动示例 JVM，并可用 Launcher attach 演示命令。

#### Scenario: Docker Demo 默认启动示例 JVM
前置：构建 demo 镜像  
- 容器启动后默认运行 `com.javasleuth.test.EnhancedTestApplication`
- `docker exec -it ... ./sleuth.sh` 可正常进入 `sleuth>` 并演示命令

## Risk Assessment
- **Risk:** 目录迁移与 jar 路径变化导致脚本/文档失效
  - **Mitigation:** 全量更新内部引用路径；关键脚本保持自动定位（优先 `core/target`，兼容旧 `target`）
- **Risk:** Maven 多模块插件继承/执行范围不当导致 examples 被错误打包或构建失败
  - **Mitigation:** 仅在 `core` 模块配置 assembly/animal-sniffer 等插件；`examples` 保持最小配置

