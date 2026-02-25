## How

### 1) 统一参数解析与校验层（CommandArgs 扩展）

策略：在 `com.javasleuth.command.CommandArgs` 中补齐数值解析/取参能力，提供：

- 默认值：缺省参数使用默认值（例如 intervalMs 默认 1000）。
- 解析与错误码：显式区分 **缺参/非法格式/越界**，并用统一错误码输出（如 `E_ARGS_MISSING` / `E_ARGS_INVALID` / `E_ARGS_RANGE`）。
- 范围限制：对可能造成性能/输出爆炸的参数设置合理上下限（如 hot limit、monitor interval）。

### 2) 在 StackLegacyOperations 应用统一解析与范围限制

覆盖点：

- `stack dump [thread-id]`：threadId 必须为正数。
- `stack monitor start [intervalMs]`：intervalMs 必须为整数并限制在安全范围。
- `stack analyze [limit]` / `stack hot [limit]`：limit 必须为整数并限制范围，避免 `Stream.limit(<0)` 异常与输出爆炸。

同时修复该模块内的 Locale 大小写归一化（使用 `Locale.ROOT` 或 `regionMatches(ignoreCase=true)`）。

### 3) 修复吞异常并补齐 DEBUG/WARN 日志

原则：

- **best-effort** 行为仍保持 best-effort（不中断启动/主流程）。
- 但在 `catch (Exception ignore)` 场景下至少输出 `DEBUG` 日志（必要时 `WARN`），并携带异常信息，避免“黑洞”。

### 4) 关键协议/执行链路 Locale 归一化修复

覆盖点：

- 握手关键字识别：避免 `toUpperCase()` 的 Locale 依赖。
- 命令名归一化：统一使用 `toLowerCase(Locale.ROOT)`，避免默认 Locale 影响 registry 查找与授权策略。

### 5) 测试与知识库同步

- 增加单测覆盖 `CommandArgs` 的错误码与范围校验分支。
- 运行 `mvn test` 验证。
- 同步 `helloagents/wiki/modules/command.md` 与 `helloagents/CHANGELOG.md`，并按流程迁移方案包到 `helloagents/history/2026-02/`。

