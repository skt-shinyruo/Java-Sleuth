# Task List: reattach_bootstrap_gate_reset

Directory: `helloagents/plan/202602211835_reattach_bootstrap_gate_reset/`

---

## 1. agent（Bootstrap 入口闩锁可重入）
- [√] 1.1 新增内部门闩类 `com.javasleuth.agent.BootstrapAttachGate` 于 `agent/src/main/java/com/javasleuth/agent/BootstrapAttachGate.java`，提供 `tryEnter/releaseOnFailure/resetForReattach`，验证 why.md#requirement-startup-failure-allows-retry
- [√] 1.2 改造 `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`：以 gate 替代静态 `ATTACHED`，并在 core jar 缺失/反射失败/调用抛错时回滚 gate；成功调用 core 后不回滚，验证 why.md#requirement-detach--re-attach-works-end-to-end

## 2. core（shutdown 时触发 bootstrap gate reset）
- [√] 2.1 新增 `core/src/main/java/com/javasleuth/core/agent/core/BootstrapAttachGateReset.java`：best-effort 反射 reset（system classloader 优先，instrumentation 扫描兜底），验证 why.md#risk-assessment
- [√] 2.2 修改 `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentCore.java`：在 `shutdown()` finally 中调用 `BootstrapAttachGateReset.resetBestEffort(...)`，确保 detach/stop 后可 re-attach，验证 why.md#requirement-detach--re-attach-works-end-to-end

## 3. Testing（关键路径单测）
- [√] 3.1 新增 test double `core/src/test/java/com/javasleuth/agent/BootstrapAttachGate.java`（仅测试用），用于观测 reset 行为
- [√] 3.2 新增单测 `core/src/test/java/com/javasleuth/agent/core/BootstrapAttachGateResetTest.java`：覆盖 `resetBestEffort(null)` 与 `SleuthAgentCore.shutdown()` 两条路径均可 reset gate

## 4. Security Check
- [√] 4.1 执行安全检查（G9）：反射调用固定目标（无外部输入），异常吞噬不影响 shutdown 主流程，不输出敏感信息

## 5. Documentation Update（知识库同步）
- [√] 5.1 更新 `helloagents/wiki/modules/agent.md`：补充“detach→re-attach 生命周期 SSOT”为 gate + core shutdown reset 的实现说明
- [√] 5.2 更新 `helloagents/CHANGELOG.md`：记录修复项（detach→re-attach 语义恢复 / 启动失败可重试）

## 6. Verification
- [√] 6.1 运行 `mvn test`，确保 core 单测通过
