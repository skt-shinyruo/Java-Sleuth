# Java-Sleuth

> 项目级核心信息，模块详细说明见 `modules/`。

---

## 1. Project Overview

### Goals and Background
提供类似 Arthas 的轻量 Java 诊断工具，通过动态 Attach 注入 Agent，在不中断应用的情况下进行 JVM 观察、方法追踪与热更新。

### Scope
- **In scope:** Attach/Agent、命令行诊断、字节码增强、监控与审计
- **Out of scope:** Web UI、多节点分布式控制、远程鉴权中心

### Stakeholders
- **Owner:** 项目维护团队

---

## 2. Module Index

| Module Name | Responsibility | Status | Documentation |
|-------------|----------------|--------|---------------|
| agent | Agent 入口与生命周期 | ✅Stable | [agent](modules/agent.md) |
| launcher | CLI/Attach 入口与会话 | ✅Stable | [launcher](modules/launcher.md) |
| command | 命令注册与执行 | ✅Stable | [command](modules/command.md) |
| enhancement | ASM 插桩与增强 | ✅Stable | [enhancement](modules/enhancement.md) |
| monitor | 拦截与结果分发 | ✅Stable | [monitor](modules/monitor.md) |
| monitoring | 指标采集与 MBean | ✅Stable | [monitoring](modules/monitoring.md) |
| security | 输入校验/审计/权限 | ✅Stable | [security](modules/security.md) |
| config | 运行时配置加载 | ✅Stable | [config](modules/config.md) |
| util | 性能/内存工具 | ✅Stable | [util](modules/util.md) |
| compiler | 内存编译器 | ✅Stable | [compiler](modules/compiler.md) |
| data | 监控结果模型 | ✅Stable | [data](modules/data.md) |
| test | 示例应用与测试 | ✅Stable | [test](modules/test.md) |

---

## 3. Quick Links
- [Technical Conventions](../project.md)
- [Architecture Design](arch.md)
- [API Manual](api.md)
- [Data Models](data.md)
- [Change History](../history/index.md)

