# Technical Design: Maven 多模块化（core + examples）

## Technical Solution

### Core Technologies
- Maven multi-module（parent/aggregator + modules）
- Java 8 编译基线（与现有约束保持一致）

### Implementation Key Points
1. 根 `pom.xml` 调整为 `packaging=pom`，新增 `core` 与 `examples` 模块聚合
2. `core/` 模块继承 parent 坐标与属性，承载原有依赖与构建插件（assembly / animal-sniffer / surefire）
3. `examples/` 模块新增 `examples/pom.xml`，由 Maven 编译示例源码（默认仅 jar/classes，不做 fat-jar）
4. 更新脚本与 Docker：
   - `sleuth.sh/.bat` 与 `scripts/*` 优先从 `core/target/` 寻找 fat-jar
   - `scripts/examples/compile-examples.sh` 改为使用 Maven 编译 examples，并输出兼容目录（供 demo/perf/security 复用）
5. 更新文档与知识库：统一说明构建入口与产物位置

## Architecture Decision ADR

### ADR-001: 采用 parent/aggregator + core + examples 的 Maven 多模块结构
**Context:** 单模块结构下 examples 需要脚本 `javac` 编译；构建与 IDE 体验不够标准，产物边界依赖人为约定。  
**Decision:** 根工程改为 `packaging=pom`，新增 `core/`（主产物）与 `examples/`（示例应用）两个模块。  
**Rationale:** 标准化工程组织、提升可维护性与构建一致性；明确发布边界，减少脚本特判。  
**Alternatives:** 仅为 `examples/` 增加独立 `pom.xml`（非聚合） → 放弃原因：无法保证根目录一次构建同时覆盖主产物与示例，整体一致性较弱。  
**Impact:** 目录结构与产物路径变化，需要同步调整脚本、Docker 与文档。

## Security and Performance
- **Security:** 不引入新的外部连接与凭据；仅调整构建结构与脚本路径
- **Performance:** 构建时模块化可能带来轻微 reactor 开销；运行时无变化

## Testing and Deployment
- **Testing:**
  - `mvn test`（或 `mvn verify`）在仓库根目录执行通过
  - 脚本 smoke：`./sleuth.sh` 可正常启动；demo 脚本可启动示例 JVM
- **Deployment:** 产物仍以 `core` 模块的 `*-jar-with-dependencies.jar` 为发布与部署对象

