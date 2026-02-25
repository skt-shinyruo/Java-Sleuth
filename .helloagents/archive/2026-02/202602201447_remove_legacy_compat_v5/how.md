# how: 实施方案（v5）

1. **删除 legacy 构造器**
   - 移除 command/impl 内带 `@Deprecated` 的构造器（以及内部 `getInstance()` 回退）。
   - 仅保留“注入必需依赖”的构造器，并对 null 参数 fail-fast（`IllegalArgumentException`）。

2. **更新调用点**
   - `BuiltinCommandProvider` 已走注入构造器：确认无旧构造器调用残留。
   - 更新测试与任何手动 new 的命令对象位置，使其传入显式依赖实例（优先使用 `new JobManager()` 等独立实例以减少测试串状态）。

3. **文档同步**
   - 更新知识库中关于 “legacy/bridge-only 构造器” 的描述，改为“强制注入、无 legacy 路径”。
   - `CHANGELOG.md` 补充本次破坏性变更说明（属于内部 API/构造器层面的 breaking change）。

4. **验证**
   - 执行 `mvn test`，确保多模块编译与测试通过。

