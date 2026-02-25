# Technical Design: 根目录整理（文档集中到 docs/ + 脚本归档）

## Technical Solution

### Core Technologies
- Git（`git mv` 保留历史）
- Maven（`mvn test` 验证构建不受影响）
- Shell 基础校验（`bash -n`）

### Implementation Key Points
1. **目录结构落地**
   - 创建 `docs/` 分层目录：`docs/index.md` + `docs/usage` + `docs/dev` + `docs/ops`
   - 创建 `scripts/` 分层目录：`scripts/demo` + `scripts/perf` + `scripts/deploy` + `scripts/security` + `scripts/test`

2. **文档迁移策略（不保留根目录 stub）**
   - 使用 `git mv` 将根目录文档迁移到 `docs/` 下，确保历史可追溯
   - 迁移后全局搜索并更新引用（包括 README、ops runbook 中对脚本的引用）
   - 在 `docs/index.md` 给出统一入口索引，减少用户记忆具体文件名的成本

3. **脚本迁移策略**
   - 优先只做路径迁移，不修改脚本逻辑
   - 迁移后补充 `scripts/README.md`：说明脚本分类与常用入口
   - 更新文档中引用脚本路径的示例命令

4. **可回滚与风险控制**
   - 变更以“移动文件 + 更新引用”为主，风险集中在链接断裂
   - 通过全局搜索与测试验证降低风险

## Security and Performance
- **Security:** 不引入新安全风险；仅涉及文档与脚本路径调整
- **Performance:** 不影响运行时性能；仓库结构更清晰可降低维护成本

## Testing and Deployment
- **Testing:**
  - 执行 `mvn test`（确保构建与测试流程不受影响）
  - 对迁移后的脚本执行 `bash -n`（基础语法校验）
  - 全仓库搜索确认不存在旧路径引用（关键文件名/旧路径片段）
- **Deployment:**
  - 不涉及部署流程变更；仅需要更新部署文档里的脚本路径示例

