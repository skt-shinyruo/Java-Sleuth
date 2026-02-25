# 变更提案：docs 文档中文化

## 需求背景

当前仓库的 `docs/` 目录文档存在中英文混用的情况，影响团队阅读体验与文档一致性。为降低沟通成本、提升可维护性，需要将 `docs/` 下的说明性内容统一为简体中文。

## 变更内容

1. 将 `docs/` 下 Markdown 文档的标题、段落、列表与表格说明文字翻译为简体中文
2. 保留代码块/命令示例/配置 key/路径等可复制内容，避免影响实际操作
3. 对包含目录（Table of Contents）的文档补充稳定锚点，避免翻译后内部链接失效

## 影响范围

- **模块**：文档（`docs/`）
- **文件**：
  - `docs/index.md`
  - `docs/usage/commands.md`
  - `docs/dev/implementation-summary.md`
  - `docs/dev/testing-report.md`
  - `docs/ops/operations-runbook.md`
  - `docs/ops/production-deployment-guide.md`
- **API**：无
- **数据**：无

## 核心场景

### Requirement: docs 文档中文化
**Module:** docs
将 `docs/` 目录中的用户/开发/运维文档统一为中文，确保阅读一致性。

#### Scenario: 开发/运维日常查阅
在排障、部署、测试、命令使用等场景下阅读 `docs/` 文档：
- 期望文档说明文字均为简体中文
- 期望命令/配置/示例保持原样可直接复制执行
- 期望文档内部目录链接可正常跳转

## 风险评估

- **风险：** 翻译后标题变化导致目录锚点失效
  - **缓解：** 对目录型文档添加显式锚点（`<a id="..."></a>`）并同步目录链接
- **风险：** 个别术语翻译不统一或语义偏差
  - **缓解：** 保留关键专有名词/命令名/配置 key；翻译以“可操作、可验证”为准

