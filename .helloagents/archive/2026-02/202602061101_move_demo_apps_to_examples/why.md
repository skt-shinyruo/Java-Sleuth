# Change Proposal: 将示例/测试应用迁移到 examples/（清理生产产物边界）

## Requirement Background
当前仓库存在演示/测试用的示例应用：

- `src/main/java/com/javasleuth/test/TestApplication.java`
- `src/main/java/com/javasleuth/test/EnhancedTestApplication.java`

它们位于 **main 源集**，会默认进入发布 jar（尤其是 `maven-assembly-plugin` 产出的 `*-jar-with-dependencies.jar` fat-jar），从而带来：

- 产物边界不够干净：容易产生“这是生产能力还是 demo？”的认知负担
- 依赖扫描/许可证审核/攻击面评估引入噪音（把 demo 类当成生产代码的一部分）

本变更目标是在**不影响既有 demo / 回归脚本可用性**的前提下，明确“生产能力 vs 示例应用”的边界。

## Change Content
1. 将 `com.javasleuth.test.*` 示例应用源码从 `src/main/java` 迁移到 `examples/`（独立目录承载）。
2. 统一提供“编译并运行 examples”的脚本入口，供 demo / 回归脚本 / Docker demo 复用。
3. 调整 Docker demo、脚本与文档，使其不再依赖 fat-jar 内置示例类。
4. 同步更新知识库（模块说明与使用约定），避免知识与代码不一致。

## Impact Scope
- **Modules:** build/package、scripts、docker、docs、examples（新增目录约定）
- **Files:** 迁移示例源码 + 更新脚本/文档/Dockerfile（详见 task.md）
- **APIs:** N/A
- **Data:** N/A

## Core Scenarios

<a id="req-artifact-boundary"></a>
### Requirement: 产物边界干净（不携带 demo/test app）
**Module:** build/package
发布 jar 与 fat-jar 不应包含示例应用类。

<a id="scn-fat-jar-without-demo"></a>
#### Scenario: 构建 fat-jar 不包含示例类
前置：执行 `mvn clean package`
- `target/*-jar-with-dependencies.jar` 中不再出现 `com/javasleuth/test/*`
- 依赖扫描/许可证审核/安全评估面向产物时，不再将示例类视为生产代码

<a id="req-demo-still-works"></a>
### Requirement: Demo 仍可运行（用于 attach 演示）
**Module:** docker/scripts/docs
示例 JVM 进程仍可被启动，便于用户手工 attach 演示与本地回归。

<a id="scn-docker-demo"></a>
#### Scenario: Docker demo 默认运行 EnhancedTestApplication
前置：`docker build ...` + `docker run ...`
- 容器默认启动 `EnhancedTestApplication`（来自 examples 编译输出，而非发布 jar）
- `docker exec -it <container> ./sleuth.sh` 后，仍可在进程列表选择该 PID 演示 `dashboard/thread/sc/sm/watch/trace`

<a id="scn-local-demo-scripts"></a>
#### Scenario: 本地 demo 脚本可直接启动示例进程
前置：主项目构建完成
- `scripts/demo/demo.sh` 能启动 `TestApplication`
- `scripts/demo/demo-comprehensive.sh` 能启动 `EnhancedTestApplication`

<a id="req-regression-scripts"></a>
### Requirement: 回归/安全/性能脚本兼容
**Module:** scripts
仓库内已有回归/安全/性能脚本对示例应用存在依赖，应保持可用。

<a id="scn-security-perf-regression"></a>
#### Scenario: 脚本仍能启动目标 JVM 并执行核心验证
前置：主项目构建完成
- `scripts/test/test-all-commands.sh`、`scripts/security/security-test.sh`、`scripts/perf/performance-test.sh` 仍可启动目标 JVM
- `watch/trace` 等命令示例与示例应用的实际方法保持一致（避免脚本失真）

## Risk Assessment
- **Risk:** examples 目录默认不参与 Maven 编译，demo 需要额外的“编译 examples”步骤
  - **Mitigation:** 提供统一的编译脚本（输出到 `target/examples-classes`，避免污染 `target/classes`），Docker/脚本统一复用
- **Risk:** Docker demo 过去依赖 fat-jar 内置示例类，迁移后需要同步调整
  - **Mitigation:** Dockerfile 在镜像构建阶段编译 examples 并作为默认启动 classpath
- **Risk:** 现有脚本对示例类的方法名/行为可能与当前实现不一致
  - **Mitigation:** 在改造脚本时同步校正 `watch/trace` 目标方法，加入最小可运行校验步骤

