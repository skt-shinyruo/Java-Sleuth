# why: 去除 legacy 兼容（强制注入路径）

## 背景

在 `compress_global_state_v4` 中，为了降低回归风险，我们在多处命令实现类中保留了带 `@Deprecated` 的 legacy 构造器（内部会隐式调用 `getInstance()`），并通过装配层优先走注入构造器来“收口”默认路径。

但这仍然存在两个问题：

1. **依赖来源不透明**：legacy 构造器仍允许绕过装配层，重新引入隐式全局获取。
2. **治理边界不够硬**：代码审查/后续贡献者可能继续沿用旧构造器，导致单例/静态状态蔓延反弹。

## 本次目标

- 明确决策：**不再兼容 legacy 构造器/隐式回退**。
- 让编译期直接约束：命令对象必须显式注入所需依赖（JobManager/VmToolSessionRegistry/PerformanceOptimizer/AuthenticationManager 等）。
- 对外行为不变（命令语义/协议/输出保持一致），只改变装配与依赖来源的“硬约束”。

