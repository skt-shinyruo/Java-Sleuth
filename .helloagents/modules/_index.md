# 模块索引

> 通过此文件快速定位模块文档（由 ~upgradekb 升级生成）

## 模块清单

| 模块 | 职责 | 状态 | 文档 |
|------|------|------|------|
| agent | Java Agent 入口与隔离加载（bootstrap → container → core） | 📝 | [agent.md](./agent.md) |
| bootstrap | Bootstrap 可见桥接层（spy/bridge，JDK-only） | 📝 | [bootstrap.md](./bootstrap.md) |
| command | 命令协议、注册与执行管线 | 📝 | [command.md](./command.md) |
| compiler | 编译/反编译/字节码辅助工具与约定 | 📝 | [compiler.md](./compiler.md) |
| config | 配置加载、运行时覆写与生命周期 | 📝 | [config.md](./config.md) |
| data | 数据模型与跨边界传递对象 | 📝 | [data.md](./data.md) |
| enhancement | 插桩增强与 transformer 管理 | 📝 | [enhancement.md](./enhancement.md) |
| launcher | 本机启动器/客户端（交互与脚本模式） | 📝 | [launcher.md](./launcher.md) |
| monitor | 监控类命令与采样输出 | 📝 | [monitor.md](./monitor.md) |
| monitoring | 监控指标与采样/存储对接 | 📝 | [monitoring.md](./monitoring.md) |
| security | 认证/授权/风险控制与审计 | 📝 | [security.md](./security.md) |
| test | 测试策略与回归约定 | 📝 | [test.md](./test.md) |
| util | 通用工具与基础设施 | 📝 | [util.md](./util.md) |

## 模块依赖关系（高层）

```
launcher → agent → container → core
bootstrap → (agent/core)
foundation → (core/launcher)
```

## 状态说明
- ✅ 稳定
- 🚧 开发中
- 📝 文档整理中
