# Task List: 架构边界收敛 + 生命周期治理 + 单例显式化（v2）

Directory: `helloagents/plan/202602132323_arch_boundary_lifecycle_singletons/`

---

## 1. Launcher（协议客户端解耦）
- [√] 1.1 为 `ProtocolClient` 增加可注入 `RequestSecurityManager` 的构造/连接重载，并保持默认行为不变；验证 why.md#requirement-launcher-端协议客户端可替换-signer（降低单例隐式依赖）

## 2. Core（命令生命周期纳管 + vmtool 闭环）
- [√] 2.1 在 `CommandRegistry.shutdown()` 中对实现 `AutoCloseable` 的命令执行 best-effort `close()`，并保证关闭顺序先命令后 plugin classloader；验证 why.md#requirement-stop-detach-后不残留-profiler-定时线程
- [√] 2.2 让 `ProfilerCommand` 实现 `AutoCloseable`，在 close 时安全停止 scheduler；验证 why.md#requirement-stop-detach-后不残留-profiler-定时线程
- [√] 2.3 在 `SleuthAgentCore.shutdown()` 中补齐 vmtool sessions/interceptor 的 stopAll/clearAll；验证 why.md#requirement-detach-re-attach-后不残留-vmtool-运行态

## 3. Bootstrap/Agent（重复实现收敛）
- [√] 3.1 在 `JarLocator` 提供通用 CodeSource jar 定位方法，并在 `SleuthAgent` 复用；验证 why.md#requirement-detach-re-attach-后不残留-vmtool-运行态（间接）与 why.md#change-content

## 4. Security Check
- [√] 4.1 复核 shutdown 路径的 best-effort 策略：避免在目标 JVM 抛异常/死锁；避免泄露 sessionId/secret 到日志；符合 G9

## 5. Documentation Update
- [√] 5.1 同步更新知识库（`helloagents/wiki/modules/*.md` + `helloagents/CHANGELOG.md` + `helloagents/history/index.md`）

## 6. Testing
- [√] 6.1 新增或补齐单测：vmtool shutdown 清理与 profiler close 行为，确保 `mvn test` 通过
