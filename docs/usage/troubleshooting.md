# Troubleshooting（常见故障排查）

本文收集 Java-Sleuth 在使用过程中最常见的失败场景与排查路径，重点覆盖：

- Attach 失败/找不到目标 JVM
- bootstrap bridge 缺失导致插桩不可用
- 连接不上命令端口/握手失败
- 认证 / RBAC / 危险命令确认相关问题

> 命令与参数以运行时 `help` 输出为准；本文提供“最短排查路径”。

---

## 1. Launcher 找不到 agent/container jar

**症状**
- `./sleuth.sh` 启动后提示找不到 agent jar / container jar
- 不在项目根目录运行时更容易发生

**常见原因**
- 没有执行 `mvn clean package`（缺少 `*/target/*-jar-with-dependencies.jar`）
- Launcher 未能从当前工作目录扫描到 jar（或你显式禁止了 CWD 扫描）

**处理**
1. 先构建一次：
   ```bash
   mvn clean package
   ```
2. 不在项目根目录运行时，显式指定 jar 路径（示例，按你的实际路径修改）：
   - `-Dsleuth.agent.container.jar=container/target/java-sleuth-container-*-jar-with-dependencies.jar`
   - `-Dsleuth.agent.jar=agent/target/java-sleuth-agent-*-jar-with-dependencies.jar`

详见：`getting-started.md`。

---

## 2. Attach 失败 / 列表里没有目标 JVM

**症状**
- 进程列表为空，或 attach 报错（例如权限不足、Attach API 不可用等）

**常见原因**
- Java 8 下使用了 JRE（缺少 `tools.jar`），需要 JDK
- Java 9+ 下运行环境被裁剪（缺少 `jdk.attach` 模块）
- 用户权限不一致（用 A 用户启动目标 JVM，却用 B 用户 attach）
- 容器/安全策略限制 Attach（某些 hardened 容器/运行时会禁用）

**处理**
- 确认使用完整 JDK 运行 Launcher
- IDE 调试场景（JDK 9+）可显式加：
  - `--add-modules jdk.attach`（见 `../dev/intellij-idea-debugging.md`）
- 尽量用同一用户执行 attach
- 目标 JVM 在容器内：建议在容器内运行 `./sleuth.sh` 并 attach（避免跨 namespace/权限问题）

---

## 3. `watch/trace/monitor/stack/...` 提示 bridge 不可用

**症状**
- 提示 `bootstrap bridge unavailable`（或类似信息）
- 插桩命令拒绝启用，避免 `NoClassDefFoundError/LinkageError`

**原因**
- 增强后的业务字节码会直接调用 `com.javasleuth.bootstrap.*` 的拦截器类；
  如果 bridge jar 没有成功追加到 `BootstrapClassLoader` 搜索路径，业务类会“看不见”拦截器，从而崩溃。

**处理**
- 按 `getting-started.md` 的提示显式指定 bridge jar（按实际路径修改）：
  - `-Dsleuth.agent.bootstrap.bridge.jar=agent/target/java-sleuth-bootstrap-bridge-*.jar`
  - 或环境变量 `SLEUTH_AGENT_BOOTSTRAP_BRIDGE_JAR`
- 深入原理与边界：`../tutorial/attach-and-instrumentation.md`

---

## 4. 连接不上端口 / Connection refused / 连接后立刻断开

**排查顺序**
1. **确认 bind 与端口配置**
   - `server.bind.address`（建议本机排障用 `127.0.0.1`）
   - `server.port`（默认 `3658`）

2. **确认端口是否已监听**
   ```bash
   lsof -i :3658
   # 或
   nc -zv 127.0.0.1 3658
   ```

3. **确认是否被安全策略阻止启动**
   - Java-Sleuth 命令服务端为 **loopback-only**：若配置为非回环地址（如 `0.0.0.0` / 局域网 IP），服务会拒绝启动（fail-fast）

生产建议与配置细节：`../ops/production-deployment-guide.md`、`../ops/operations-runbook.md`。

---

## 5. 旧版 HMAC/SIG 相关报错（连接建立但命令执行失败）

**常见提示（示例）**
- `Unsupported request envelope: SIG (HMAC mode removed)`
- `Unsupported config key: security.mode (HMAC mode removed; server is loopback-only)`
- `Unsupported config key: security.hmac.secret (HMAC mode removed; server is loopback-only)`

**处理**
- 删除旧配置项：`security.mode`、`security.hmac.*`、`security.bootstrap.hmac.*`（这些 key 已被移除/禁用）
- 不要使用旧客户端发送 `SIG ...` 包裹的命令；请使用最新 Launcher（`./sleuth.sh` / `sleuth.bat`）

---

## 6. RBAC/权限不足（命令被拒绝）

**症状**
- 报错提示需要更高权限，或被 Authorization 拒绝

**处理**
1. 先看当前会话角色：
   - `session`
   - `perm`
2. 默认匿名 viewer 已关闭，未认证连接不会获得会话；如需本地开发只读匿名访问，显式设置 `security.anonymous.viewer=true`。若需要 operator/admin 权限，启用口令认证（`security.auth.password.enabled=true`）并设置对应密码后，用：
   - `auth <username> <password>`
   - headless/restart 可用 Launcher 参数：`--auth-user <username> --auth-pass <password>`

> 注意：`security.authorization.enabled=true` 是默认值。多人共享主机上建议保持默认授权、开启口令认证并设置强口令；不要通过关闭授权来绕过权限问题。

---

## 7. 危险命令需要二次确认（Dangerous confirm）

**症状**
- 执行 `redefine/retransform/mc/heapdump/reset/stop/...` 返回“需要确认 token”

**处理**
- 在 TTL 内按提示追加 `--confirm <token>` 重试
- 或在配置中关闭二次确认（不推荐）

---

## 8. 排障结束后的清理建议

- `reset`：清空 active 增强与会话，best-effort 尝试回滚字节码
- `stop`：停止目标 JVM 内的 Java-Sleuth（关闭命令服务与 transformer）

回滚机制与边界说明：`../tutorial/command-instrumentation-and-rollback.md`。
