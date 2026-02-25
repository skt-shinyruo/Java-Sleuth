# 项目上下文

## 1. 基本信息

```yaml
名称: Java-Sleuth
描述: 以 Java Agent + Attach + 命令协议 为核心的 JVM 诊断工具链
类型: CLI工具 + Java Agent
状态: 开发中（持续迭代）
```

## 2. 技术上下文

```yaml
语言: Java 8
框架: 无（以 JDK API + 自研组件为主）
包管理器: Maven
构建工具: Maven
```

### 主要依赖
| 依赖 | 版本 | 用途 |
|------|------|------|
| ASM | 9.x | 字节码增强 |
| Jackson | 2.x | 配置/协议数据序列化 |
| JLine | 3.x | 交互式 CLI |
| CFR (jad) | - | 反编译/诊断辅助 |
| RE2/J | - | 安全正则（避免灾难性回溯） |

## 3. 项目概述

### 核心功能
- 本机 `launcher` 选择目标 JVM → Attach → 动态加载 Agent
- 目标 JVM 内启动命令服务端（Command Server）
- 命令协议握手协商，支持交互模式与脚本化模式

### 项目边界
```yaml
范围内:
  - Attach/Detach 生命周期编排
  - 命令协议与诊断能力（watch/trace/monitor/tt 等）
  - 依赖隔离与安全边界控制
范围外:
  - 业务监控平台/采集平台的一体化交付（可通过输出对接）
  - 对目标 JVM 进行破坏性/不可逆变更（默认禁止/需二次确认）
```

## 4. 开发约定

### 代码规范
```yaml
命名风格: Java 类/方法使用驼峰命名；命令名使用小写
文件命名: 维持现有风格与命名习惯，避免引入新的格式化规则
目录组织: 多模块 Maven 工程，按职责边界拆分（bootstrap/foundation/agent/container/core/launcher/examples）
```

### 错误处理
```yaml
策略: 命令执行失败返回可读错误信息，避免影响目标 JVM 稳定性
日志: 控制台日志 + 审计日志（可通过配置关闭）
```

### 测试要求
```yaml
测试框架: JUnit 4
覆盖率要求: 未强制（以关键边界回归为主）
测试命令: mvn test
```

### Git规范
```yaml
分支策略: 未在知识库中强制约定
提交格式: 建议保持简洁可追溯
```

## 5. 当前约束（源自历史决策）

> 该部分为摘要；详细说明以 `.helloagents/project.md` 与历史方案包为准。

| 约束 | 原因 | 决策来源 |
|------|------|---------|
| 运行时基线为 Java 8 | 需要在更广泛环境下稳定运行 | `.helloagents/project.md` |
| 优先依赖隔离（避免业务依赖碰撞） | 降低 LinkageError/行为漂移风险 | `.helloagents/project.md` |
| 危险命令需二次确认（可选开启） | 降低误操作与安全风险 | `.helloagents/CHANGELOG.md` |

## 6. 已知技术债务（可选）

| 债务描述 | 优先级 | 来源 | 建议处理时机 |
|---------|--------|------|-------------|
| （待补充） | P2 | - | 迭代中持续补齐 |

---

### 参考
- `.helloagents/project.md`（项目概览 SSOT）
- `.helloagents/wiki/`（legacy 文档，升级后仍保留）