# Task List: Maven 多模块化（core + examples）

Directory: `helloagents/plan/202602061645_maven_multi_module/`

---

## 1. Maven Build Structure
- [√] 1.1 将根 `pom.xml` 改为 parent/aggregator（`packaging=pom`）并声明 `core`、`examples` 模块，验证 why.md#core-scenarios-requirement-多模块构建与产物边界清晰-scenario-根目录执行构建
- [√] 1.2 新增 `core/pom.xml`（继承 parent），迁移原有依赖与插件配置，并将源码目录迁移到 `core/src/`，验证 why.md#core-scenarios-requirement-多模块构建与产物边界清晰-scenario-根目录执行构建
- [√] 1.3 新增 `examples/pom.xml`，使示例应用作为独立 Maven 模块可被编译/打包，验证 why.md#core-scenarios-requirement-多模块构建与产物边界清晰-scenario-根目录执行构建

## 2. Scripts / Docker Compatibility
- [√] 2.1 更新 `sleuth.sh`、`sleuth.bat`、`scripts/*`：从 `core/target/` 定位 fat-jar，验证 `./sleuth.sh` 可启动
- [√] 2.2 更新 `scripts/examples/compile-examples.sh`：使用 Maven 编译 examples 并输出 demo 复用目录，验证 why.md#core-scenarios-requirement-demo-测试脚本可用-scenario-docker-demo-默认启动示例-jvm
- [√] 2.3 更新 `docker/demo/Dockerfile` 与 `docker/demo/README.md`：构建阶段按多模块编译，运行阶段默认启动示例 JVM，验证 why.md#core-scenarios-requirement-demo-测试脚本可用-scenario-docker-demo-默认启动示例-jvm

## 3. Security Check
- [√] 3.1 执行安全检查（不引入敏感信息、不引入高风险脚本行为、避免破坏性操作扩散）

## 4. Documentation Update
- [√] 4.1 更新 `README.md`、`docs/usage/getting-started.md`、`docs/ops/production-deployment-guide.md`：同步多模块结构与产物路径
- [√] 4.2 更新知识库：`helloagents/wiki/modules/launcher.md`、`helloagents/wiki/modules/test.md` 与 `helloagents/CHANGELOG.md`

## 5. Testing
- [√] 5.1 运行 `mvn test`（根目录）验证构建与单测通过
