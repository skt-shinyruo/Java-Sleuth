## 背景

虽然上一轮已将 `CommandProcessor` 的装配点收敛到 composition root，并补齐了部分 `shutdown`/`shutdownInstance`，但仍存在两类影响长期可演进性的问题：

1) **命令实现层仍有隐式单例依赖**
- `VmToolCommand` 内部直接调用 `DangerousCommandConfirmationManager.getInstance()`。
- `AuthCommand`/`SessionCommand` 直接调用 `AuthenticationManager.getInstance()`。

这类依赖让“看代码才能知道依赖”和“测试替换/多实例隔离”变得困难，也会让 detach→re-attach 的生命周期边界变得模糊（状态是否清理、是否复用旧实例不直观）。

2) **生命周期治理仍以静态清理为主，缺少 instance 级 shutdown 语义**
当前安全/确认相关管理器主要提供 `shutdownInstance()`（静态），一旦未来我们注入自定义实例（非全局单例），关闭编排将无法统一收口这些实例的缓存状态。

## 目标（Success Criteria）

- 将关键命令（vmtool/auth/session）对安全/会话组件的依赖改为 **可注入**（保留默认构造兼容）。
- 为关键管理器补齐 **instance 级 shutdown()**，并在关闭编排中优先调用注入实例的 shutdown（必要时再 fallback 到 shutdownInstance）。
- 保持现有行为兼容：不要求调用方必须显式注入，也不改变协议与外部 CLI 用法。

