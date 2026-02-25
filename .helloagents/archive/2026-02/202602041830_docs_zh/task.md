# 任务列表：docs 文档中文化

Directory: `helloagents/plan/202602041830_docs_zh/`

---

## 1. docs 文档翻译
- [√] 1.1 翻译 `docs/index.md`（标题/说明/链接文本），保持路径与代码格式不变
- [√] 1.2 翻译 `docs/usage/commands.md` 的英文标题与说明文字，保留命令名/参数/代码块
- [√] 1.3 翻译 `docs/dev/implementation-summary.md` 的英文内容，保留代码块与路径
- [√] 1.4 翻译 `docs/dev/testing-report.md` 的英文内容（日期与数值可保留原格式），保留结构与强调
- [√] 1.5 翻译 `docs/ops/operations-runbook.md` 的英文内容，保留命令示例与配置 key
- [√] 1.6 翻译 `docs/ops/production-deployment-guide.md` 的英文内容；为目录段落补充稳定锚点并同步目录链接

## 2. 安全检查
- [√] 2.1 快速检查文档未引入敏感信息（口令/secret/真实联系方式/Token 等），并保持示例为占位符

## 3. 质量校验
- [√] 3.1 使用 `rg` 粗查 `docs/` 下是否仍存在明显英文段落（允许专有名词与代码块）
- [√] 3.2 检查 `docs/ops/production-deployment-guide.md` 的目录链接锚点可用

## 4. 文档与知识库同步
- [√] 4.1 更新 `helloagents/CHANGELOG.md` 记录本次 docs 中文化变更
- [√] 4.2 更新 `helloagents/history/index.md` 增加本次变更索引
