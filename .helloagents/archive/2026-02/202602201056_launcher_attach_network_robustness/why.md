# Change Proposal: Launcher/Attach 时序与网络健壮性增强（参考 Arthas）

## Requirement Background

当前 Launcher/Attach 的“等待时序”与网络连接策略偏脆弱，真实环境更容易出现“偶现连不上/卡住”的体验问题，典型症状包括：

1. attach 注入后用固定 `sleep` 盲等，无法保证 agent 已 ready，也缺乏总等待上限。
2. 连接前再次固定 `sleep`，仍然不是 readiness 探测，且 headless 模式缺少该等待。
3. 协议连接使用 `new Socket(host, port)` 且未显式设置 connect/read 超时；在端口不可达、网络抖动、半开连接等情况下可能出现等待时间不可控。

这些问题会让用户感知为“偶现连不上/卡住”，但实际是启动/握手阶段缺少可控的超时与重试策略。

## Change Content

1. **移除固定 sleep，改为有界等待的 ready 探测**：Launcher 在 attach 之后，通过“带总超时的连接重试（含退避）”等待 agent 服务可用，替代盲等。
2. **显式网络超时**：`ProtocolClient.connect` 在建立 socket 与握手阶段显式设置 connect timeout 与握手 read timeout，避免无限等待。
3. **统一 interactive/headless 行为**：interactive 与 headless 均使用相同的“连接重试 + 有界超时”策略，降低模式差异导致的偶现失败。
4. **可测试性增强**：增加测试覆盖“服务端延迟启动时 connectWithRetry 最终成功”的场景，防止回归。

## Impact Scope

- **Modules:**
  - launcher（Attach 编排、客户端连接与握手）
- **Files（预期）:**
  - `launcher/src/main/java/com/javasleuth/launcher/attach/AgentAttacher.java`
  - `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
  - `launcher/src/main/java/com/javasleuth/launcher/client/ProtocolClient.java`
  - `launcher/src/test/java/com/javasleuth/launcher/client/ProtocolClientIntegrationTest.java`（新增/扩展）
  - `helloagents/wiki/modules/launcher.md`（知识库同步）
  - `helloagents/CHANGELOG.md`

## Core Scenarios

### Requirement: Attach 后连接健壮性（有界等待 + 重试）
**Module:** launcher / client / attach
attach 注入后 Launcher 需要稳定连接到 agent，并避免无限等待。

#### Scenario: agent 启动监听较慢仍可连接
前置条件：
- attach 成功，但 agent 服务端启动/握手自举存在抖动（例如 0.5s~几秒）

预期结果：
- Launcher 在总超时内重试连接并最终成功进入会话
- 不再依赖固定 sleep 的“碰运气”

#### Scenario: 端口不可达时快速失败且可诊断
前置条件：
- host/port 不可达或被防火墙拦截

预期结果：
- connect 在明确的超时上限内失败（connect timeout / handshake timeout / overall timeout）
- 错误信息包含可定位线索（目标 host/port、耗时、重试次数）

## Risk Assessment

- **Risk:** 引入重试可能导致失败时“等待更久”而不是立即失败。
- **Mitigation:** 设置合理的默认总超时（参考 Arthas 的有界执行思路），并在错误信息中明确提示已重试与等待时长，避免用户误判卡死。

