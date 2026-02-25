# Technical Design: Launcher/Attach 时序与网络健壮性增强（参考 Arthas）

## Technical Solution

### Core Technologies
- Java Socket（显式 connect timeout / handshake read timeout）
- 连接重试 + 退避（bounded retry + backoff）

### Implementation Key Points

#### 1) 移除固定 sleep，使用有界连接重试等待 ready
- `AgentAttacher`：不再在 `loadAgent` 后固定 `sleep`；attach 只负责“注入成功/失败”的边界。
- `SleuthLauncher`：在 attach 成功后，统一通过 `ProtocolClient.connectWithRetry(...)` 建立会话：
  - overall timeout（总等待上限）：例如 15s（可根据现有使用习惯调整）
  - connect timeout：例如 5s（参考 Arthas 客户端 connect timeout 默认值）
  - handshake read timeout：例如 2s~5s（仅用于握手阶段，避免卡住）
  - backoff：从短间隔开始逐步退避，避免高频自旋

#### 2) ProtocolClient：显式设置 socket 超时（握手阶段）
- 替换 `new Socket(host, port)`：
  - `Socket socket = new Socket();`
  - `socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);`
  - `socket.setSoTimeout(handshakeReadTimeoutMs);`（仅用于握手 readLine/upgrade 阶段）
- 握手完成后将 `soTimeout` 恢复为 0（无限等待），避免影响 watch/trace 等长时间流式输出命令。

#### 3) 重试策略（参考 Arthas：有界执行 + 明确超时）
- 以“总时长为上限”的 while 循环做连接重试，避免无限等待：
  - 捕获 `IOException` 作为可重试失败（连接拒绝/握手超时/EOF 等）
  - 对明显不可恢复错误（如 host 为空、port 非法、UnknownHost）直接失败
- 失败时的异常信息应包含：
  - host/port
  - 总耗时/总超时
  - 尝试次数
  - 最后一次异常原因

## Security and Performance

- **Security:** 提升网络失败时的确定性与可诊断性，不涉及权限变更；避免无界等待导致脚本卡死。
- **Performance:** 重试阶段短时多次连接会有额外开销；但其发生在启动/连接阶段且有上限，可接受。

## Testing and Deployment

- **Testing:**
  - 增加集成测试：服务端（CommandProcessor）延迟启动后，`connectWithRetry` 在总超时内可成功连接并执行 `version`。
  - 维持现有握手/streaming 协商测试不变。
- **Deployment:**
  - 无额外发布步骤；仅 launcher 行为增强。

