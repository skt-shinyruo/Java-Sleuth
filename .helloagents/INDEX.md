# Java-Sleuth 知识库

> 本文件是知识库入口（由 ~upgradekb 升级生成）

## 快速导航

| 需要了解 | 读取文件 |
|---------|---------|
| 项目概况、技术栈、开发约定 | [context.md](context.md) |
| 模块索引 | [modules/_index.md](modules/_index.md) |
| 项目变更历史 | [CHANGELOG.md](CHANGELOG.md) |
| 历史方案索引 | [archive/_index.md](archive/_index.md) |
| 当前待执行的方案 | [plan/](plan/) |
| 历史会话记录 | [sessions/](sessions/) |

## 模块关键词索引

> 通过此表快速判断哪些模块与当前需求相关。

| 模块 | 关键词 | 摘要 |
|------|--------|------|
| agent | agent, java, agent, attach, protocol | Java Agent 入口与隔离加载（bootstrap → container → core） |
| bootstrap | bootstrap, java, agent, attach, protocol | Bootstrap 可见桥接层（spy/bridge，JDK-only） |
| command | command, java, agent, attach, protocol | 命令协议、注册与执行管线 |
| compiler | compiler, java, agent, attach, protocol | 编译/反编译/字节码辅助工具与约定 |
| config | config, java, agent, attach, protocol | 配置加载、运行时覆写与生命周期 |
| data | data, java, agent, attach, protocol | 数据模型与跨边界传递对象 |
| enhancement | enhancement, java, agent, attach, protocol | 插桩增强与 transformer 管理 |
| launcher | launcher, java, agent, attach, protocol | 本机启动器/客户端（交互与脚本模式） |
| monitor | monitor, java, agent, attach, protocol | 监控类命令与采样输出 |
| monitoring | monitoring, java, agent, attach, protocol | 监控指标与采样/存储对接 |
| security | security, java, agent, attach, protocol | 认证/授权/风险控制与审计 |
| test | test, java, agent, attach, protocol | 测试策略与回归约定 |
| util | util, java, agent, attach, protocol | 通用工具与基础设施 |

## 知识库状态

```yaml
kb_version: 2.2.9
最后更新: 2026-02-25 11:52
模块数量: 13
待执行方案: 0
```

## 读取指引

```yaml
启动任务:
  1. 读取本文件获取导航
  2. 读取 context.md 获取项目上下文
  3. 检查 plan/ 是否有进行中方案包

任务相关:
  - 涉及特定模块: 读取 modules/{模块名}.md
  - 需要历史决策: 搜索 CHANGELOG.md → 读取对应 archive/{YYYY-MM}/{方案包}/proposal.md
  - 继续之前任务: 读取 plan/{方案包}/*
```
