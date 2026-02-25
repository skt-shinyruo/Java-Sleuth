# Task List: Launcher/Attach 时序与网络健壮性增强（参考 Arthas）

Directory: `helloagents/plan/202602201056_launcher_attach_network_robustness/`

---

## 1. launcher / attach（移除盲等）
- [√] 1.1 调整 `launcher/src/main/java/com/javasleuth/launcher/attach/AgentAttacher.java`：移除 `loadAgent` 后固定 sleep，改由连接侧重试等待 ready，verify why.md#requirement-attach-后连接健壮性（有界等待--重试）-scenario-agent-启动监听较慢仍可连接

## 2. launcher / client（显式超时 + 重试退避）
- [√] 2.1 调整 `launcher/src/main/java/com/javasleuth/launcher/client/ProtocolClient.java`：connect 使用显式 connectTimeout/handshakeTimeout；新增 `connectWithRetry`（有界重试 + backoff + 可诊断异常），verify why.md#requirement-attach-后连接健壮性（有界等待--重试）-scenario-端口不可达时快速失败且可诊断

## 3. launcher / composition（interactive/headless 统一）
- [√] 3.1 调整 `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`：移除连接前固定 sleep；interactive/headless 均使用 `connectWithRetry`，verify why.md#requirement-attach-后连接健壮性（有界等待--重试）-scenario-agent-启动监听较慢仍可连接

## 4. Testing（回归与稳定性）
- [√] 4.1 扩展 `launcher/src/test/java/com/javasleuth/launcher/client/ProtocolClientIntegrationTest.java`：增加“服务端延迟启动后仍能 connectWithRetry 成功”的测试
- [√] 4.2 执行 `mvn test`

## 5. Documentation Update（知识库同步）
- [√] 5.1 更新 `helloagents/wiki/modules/launcher.md`：记录 attach/连接的有界等待、超时与重试策略
- [√] 5.2 更新 `helloagents/CHANGELOG.md` 记录该增强
