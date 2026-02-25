# Task List: 握手协商 + 严格帧协议 + 插件授权治理

Directory: `helloagents/plan/202601281301_sleuth_handshake_secure_frames/`

---

## 1. 跨进程握手协商（解决问题 1）
- [√] 1.1 设计并实现 HELLO/CONFIG 握手在 `src/main/java/com/javasleuth/command/CommandProcessor.java`, `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`, verify why.md#requirement-cross-process-configuration-consistency-scenario-helloconfig-handshake
- [√] 1.2 支持配置文件路径/agentArgs 注入在 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`, `src/main/java/com/javasleuth/agent/SleuthAgent.java`, `src/main/java/com/javasleuth/config/ProductionConfig.java`, verify why.md#requirement-cross-process-configuration-consistency-scenario-helloconfig-handshake

## 2. 严格二进制帧协议（解决问题 2）
- [√] 2.1 新增二进制帧编解码在 `src/main/java/com/javasleuth/command/protocol/BinaryFrame.java`, `src/main/java/com/javasleuth/command/protocol/BinaryFrameCodec.java`, verify why.md#requirement-strict-framing-protocol-scenario-long-output-and-newlines
- [√] 2.2 CommandProcessor/Launcher 切换到二进制帧通道（保留 legacy/framed 文本兼容）在 `src/main/java/com/javasleuth/command/CommandProcessor.java`, `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`, verify why.md#requirement-strict-framing-protocol-scenario-long-output-and-newlines

## 3. 插件授权治理（解决问题 3）
- [√] 3.1 扩展 CommandMeta 与 registry governance（动态权限注册/统计）在 `src/main/java/com/javasleuth/command/CommandMeta.java`, `src/main/java/com/javasleuth/command/CommandRegistry.java`, verify why.md#requirement-plugin-authorization-governance-scenario-plugin-command-with-required-role
- [√] 3.2 AuthorizationManager 支持动态命令权限注册/刷新在 `src/main/java/com/javasleuth/security/AuthorizationManager.java`, verify why.md#requirement-plugin-authorization-governance-scenario-plugin-command-with-required-role

## 4. 安全传输与防重放（解决问题 4）
- [√] 4.1 新增 security.mode 与 secret 管理；默认 `security.mode=off` 并新增 `server.bind.address=127.0.0.1` 安全默认在 `src/main/java/com/javasleuth/config/ProductionConfig.java`, `src/main/resources/sleuth-default.properties`, `config-templates/production-sleuth.properties`, verify why.md#requirement-secure-transport--replay-protection-scenario-signed-requests
- [√] 4.2 实现 HMAC+nonce 校验与重放保护在 `src/main/java/com/javasleuth/security/RequestSecurityManager.java`, `src/main/java/com/javasleuth/command/CommandProcessor.java`, `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`, verify why.md#requirement-secure-transport--replay-protection-scenario-signed-requests

## 5. 自保护、可观测性与测试（解决问题 5）
- [√] 5.1 Enhancer 插桩失败回退与计数上报在 `src/main/java/com/javasleuth/enhancement/SleuthClassFileTransformer.java`, `src/main/java/com/javasleuth/command/impl/StatusCommand.java`, verify why.md#requirement-self-protection--observability-scenario-enhancer-failure-rollback
- [√] 5.2 事件丢弃/采样指标与命令输出在 `src/main/java/com/javasleuth/monitor/WatchInterceptor.java`, `src/main/java/com/javasleuth/monitor/TraceInterceptor.java`, `src/main/java/com/javasleuth/command/impl/StatusCommand.java`, verify why.md#requirement-self-protection--observability-scenario-load-shedding
- [√] 5.3 新增协议与安全单测在 `src/test/java/com/javasleuth/command/CommandProcessorTest.java`, verify why.md#requirement-strict-framing-protocol-scenario-long-output-and-newlines

## 6. Security Check
- [√] 6.1 Execute security check (per G9: keys/PII, replay protection, plugin trust boundary, dangerous command confirmation)

## 7. Documentation Update
- [√] 7.1 Update `helloagents/wiki/` 相关模块文档与 ADR 索引（arch/api/security/command）
