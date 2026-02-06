# Task List: 示例/测试应用迁移到 examples/（清理生产产物边界）

Directory: `helloagents/plan/202602061101_move_demo_apps_to_examples/`

---

## 1. Examples 源码迁移
- [√] 1.1 新增 `examples/src/main/java/com/javasleuth/test/` 并迁移示例应用源码（`TestApplication`、`EnhancedTestApplication`），验证 why.md#req-artifact-boundary、why.md#req-demo-still-works
- [√] 1.2 删除 `src/main/java/com/javasleuth/test/*` 并全局检索确认生产代码未引用示例类，验证 why.md#req-artifact-boundary

## 2. Examples 编译入口（脚本复用）
- [√] 2.1 新增统一编译脚本（建议：`scripts/examples/compile-examples.sh`），将 examples 编译到 `target/examples-classes`，验证 why.md#req-demo-still-works

## 3. Demo 与回归脚本适配
- [√] 3.1 更新 `scripts/demo/demo.sh`：改为编译并运行 examples 中的 `TestApplication`，验证 why.md#scn-local-demo-scripts
- [√] 3.2 更新 `scripts/demo/demo-comprehensive.sh`：改为编译并运行 examples 中的 `EnhancedTestApplication`，验证 why.md#scn-local-demo-scripts
- [√] 3.3 更新 `scripts/test/test-all-commands.sh`：确保 `watch/trace` 目标方法与示例应用实现一致，验证 why.md#req-regression-scripts
- [√] 3.4 更新 `scripts/security/security-test.sh`：示例进程启动方式切换为 examples，验证 why.md#scn-security-perf-regression
- [√] 3.5 更新 `scripts/perf/performance-test.sh`：示例进程启动方式切换为 examples，验证 why.md#scn-security-perf-regression

## 4. Docker demo 适配
- [√] 4.1 更新 `docker/demo/Dockerfile`：复制并编译 examples 到独立目录，默认启动 `EnhancedTestApplication`，验证 why.md#scn-docker-demo
- [√] 4.2 更新 `docker/demo/README.md`：同步说明示例应用来源与启动方式，验证 why.md#scn-docker-demo

## 5. 文档与知识库同步（SSOT）
- [√] 5.1 更新 `docs/usage/getting-started.md`：说明 Docker demo 与示例应用来自 examples（不在发布 jar 内），验证 why.md#req-demo-still-works
- [√] 5.2 更新知识库：至少同步 `helloagents/wiki/modules/test.md`（必要时补充/重命名模块说明与索引），验证 why.md#req-artifact-boundary

## 6. Security Check
- [√] 6.1 执行安全/合规自检（G9）：确认示例类不进入发布产物、脚本不引入明文敏感信息与不必要权限操作

## 7. Testing
- [√] 7.1 执行 `mvn test` / `mvn verify` 并校验 fat-jar 内容不包含 `com/javasleuth/test/*`
- [√] 7.2 验证 Docker demo：可构建、容器默认示例进程可见、`./sleuth.sh` 可 attach 并执行 `sc/sm/watch/trace`
