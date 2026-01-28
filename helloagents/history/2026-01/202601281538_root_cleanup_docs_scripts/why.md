# Change Proposal: 根目录整理（文档集中到 docs/ + 脚本归档）

## Requirement Background
当前仓库根目录同时堆放了较多脚本与文档（demo/perf/deploy/security/test 等脚本，以及多份 runbook/guide/report 文档），导致：

1. 新成员进入项目时难以快速定位“应该看什么 / 应该怎么跑”
2. 根目录噪音较大，重要入口（README、pom.xml、sleuth.sh/sleuth.bat 等）不够突出
3. 文档与脚本路径缺乏统一组织，后续新增内容容易进一步发散

本变更目标是将“文档集中到 docs/”，并将“非入口脚本归档到 scripts/”，从结构上降低维护成本与阅读成本。

## Change Content
1. 新增 `docs/` 目录并进行分层（usage/dev/ops），将现有根目录文档迁移到 `docs/` 下
2. 新增 `scripts/` 目录并按用途分层（demo/perf/deploy/security/test），将现有根目录脚本迁移到 `scripts/` 下
3. 更新仓库内所有对旧路径的引用（README、文档互链、脚本说明等），不保留根目录旧文件“跳转 stub”（按用户选择）
4. 保留必要的根目录“入口”文件（如 `README.md`、`pom.xml`、`.gitignore`、`sleuth.sh`、`sleuth.bat`），使根目录更聚焦

## Impact Scope
- **Modules:** 无（不涉及运行时代码逻辑修改）
- **Files:** 多个文档与脚本文件将发生路径变更（git mv）
- **APIs:** 无
- **Data:** 无

## Core Scenarios

### Requirement: 文档集中到 docs/
**Module:** Documentation
将根目录文档迁移到 `docs/` 分层目录，并提供 `docs/index.md` 作为统一入口索引。

#### Scenario: 新成员查阅文档
首次进入项目后，用户从 `README.md` 进入 `docs/index.md`，可以在 1 屏内定位到：
- 使用命令（commands）
- 测试/实现说明（dev）
- 运维与部署手册（ops）

### Requirement: 脚本归档到 scripts/
**Module:** Scripts
将功能性脚本按用途归档到 `scripts/*`，避免根目录脚本散落。

#### Scenario: 运维人员执行部署/性能脚本
用户从 `docs/ops/*` 文档直接复制可执行命令，路径指向 `scripts/deploy/*`、`scripts/perf/*`，无需在根目录“找脚本”。

## Risk Assessment
- **Risk:** 迁移后旧路径失效（外部链接、历史笔记、内部引用）导致“找不到文件”
- **Mitigation:**
  1. 在提交前全仓库搜索旧路径关键字并更新引用
  2. 迁移后运行 `mvn test` 确保不影响构建流程
  3. 对脚本执行路径进行最小化变更（优先只移动，不改逻辑），并用 `bash -n` 做基础语法校验

