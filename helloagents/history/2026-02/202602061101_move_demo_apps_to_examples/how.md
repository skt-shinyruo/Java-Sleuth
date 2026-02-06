# Technical Design: 示例/测试应用迁移到 examples/（清理生产产物边界）

## Technical Solution

### Core Technologies
- Java 8
- Maven（单模块构建保持不变）
- `maven-assembly-plugin`（`jar-with-dependencies` fat-jar）
- Bash scripts（demo / regression / security / perf）
- Docker（demo 镜像）

### Implementation Key Points
1. **新增 examples 目录约定**
   - 采用 Maven 风格目录结构：`examples/src/main/java/...`
   - 示例应用源码从 `src/main/java/com/javasleuth/test/` 迁移至 `examples/src/main/java/com/javasleuth/test/`
   - 目的：从源头切断“示例应用进入生产产物”的路径

2. **统一 examples 的编译输出目录**
   - 提供脚本（如 `scripts/examples/compile-examples.sh`）将 examples 编译到 `target/examples-classes`
   - 关键约束：**禁止**输出到 `target/classes`（避免被主 jar/fat-jar 意外打包）

3. **脚本与 Docker demo 统一复用编译入口**
   - `scripts/demo/*.sh`、`scripts/test/*`、`scripts/security/*`、`scripts/perf/*` 调整为：
     - 先编译 examples
     - 再以 `-cp target/examples-classes[:target/*-jar-with-dependencies.jar]` 运行示例应用
   - Docker demo：
     - 镜像构建时复制 `examples/` 源码，并在 JDK8 环境编译到 `/opt/java-sleuth/examples-classes`
     - 默认 `CMD` 从 `/opt/java-sleuth/examples-classes` 启动 `EnhancedTestApplication`

4. **文档与知识库同步**
   - 用户文档（`docs/usage/getting-started.md`、`docker/demo/README.md`）改为说明：示例应用来自 examples，不再属于发布 jar 内容
   - 知识库（`helloagents/wiki/modules/test.md` 等）更新模块描述与运行方式，避免 SSOT 漂移

## Architecture Decision ADR
### ADR-1: 示例应用不进入生产产物（通过 examples/ 物理隔离）
**Context:** 示例应用位于 `src/main/java` 会进入 jar/fat-jar，导致产物边界不清晰并增加安全/合规噪音。  
**Decision:** 将示例应用迁移至 `examples/`，并提供独立编译输出目录与脚本入口，Docker demo 与脚本改为运行 examples 编译产物。  
**Rationale:** 物理隔离最直观、最难误用；对主构建链路影响最小；能保持 demo 能力。  
**Alternatives:** 仅在 assembly 阶段排除 `com/javasleuth/test/**` → 拒绝原因：源码边界仍混杂，无法降低“生产 vs demo”认知负担。  
**Impact:** 发布 jar/fat-jar 更干净；demo 运行链路需要显式编译 examples（由脚本/Docker 承担）。  

## Security and Performance
- **Security:**
  - 减少生产产物中的非必要类，降低攻击面与审计噪音
  - examples 编译输出独立目录，避免被打包进发布 jar
- **Performance:**
  - 对运行时无直接影响
  - examples 编译仅发生在 demo/脚本执行或 Docker build 阶段

## Testing and Deployment
- **Testing:**
  - `mvn clean package` 后校验 `jar tf target/*-jar-with-dependencies.jar` 不包含 `com/javasleuth/test/*`
  - 执行 `scripts/demo/demo.sh` 与 `scripts/demo/demo-comprehensive.sh` 启动示例进程（可被 attach）
  - 校验 `scripts/test/test-all-commands.sh`、`scripts/security/security-test.sh`、`scripts/perf/performance-test.sh` 在 examples 迁移后仍可启动目标 JVM
  - Docker demo：`docker build` + `docker run`，验证容器默认示例进程可见且 `./sleuth.sh` 可 attach
- **Deployment:**
  - 发布方式不变（fat-jar 仍由 assembly 产出）

