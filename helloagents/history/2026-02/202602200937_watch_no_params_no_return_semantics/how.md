# Technical Design: 修复 watch --no-params/--no-return 语义与插桩行为不一致

## Technical Solution

### Core Technologies
- Java + ASM（`AdviceAdapter`）
- bootstrap 拦截器 + 有界队列事件上报

### Implementation Key Points

#### 1) 事件发送与采集开关解耦（核心修复）
- 调整 `WatchEnhancer`：入口 `onMethodEnter` 与正常退出 `onMethodExit(opcode != ATHROW)` **始终注入事件回调**。
- `--no-params` / `--no-return` 只改变“传入拦截器的数据内容”，不再改变“是否注入/是否调用拦截器”。

#### 2) 数据模型增加 captured flags（避免 null 语义混淆）
- 在 `WatchResult` 增加字段：
  - `parametersCaptured`：是否采集参数
  - `returnCaptured`：是否采集返回值
  - （可选）`exceptionCaptured`：是否采集异常（与 `--no-exception` 对齐）
- 规则：
  - captured=false 时，值字段允许为 `null`，但输出层必须能区分“未采集” vs “真实 null”。

#### 3) 拦截器侧按开关决定 snapshot 行为
- `WatchInterceptor` 在收到事件时：
  - 若 `parametersCaptured=false`：不执行 `snapshotParameters`，避免不必要开销
  - 若 `returnCaptured=false`：不执行 `snapshotValue`，避免不必要开销
- 为避免对既有已插桩字节码的兼容风险，优先采用“保留旧签名 + 新签名重载/桥接”的方式：
  - 旧签名继续存在，内部转发到新逻辑并设置 captured=true（兼容旧字节码）
  - 新签名携带 captured 标记（供新插桩使用）

#### 4) 命令输出层语义对齐（默认输出 + --expr）
- `--expr` 分支：当用户显式选择 `params/return` 时：
  - 若对应 captured=false：输出 `params=<not captured>` / `return=<not captured>`（占位文案可配置为常量）
  - 若 captured=true：保持现有格式化输出
- 默认输出（`WatchResult.toString()`）：在 `--no-*` 场景输出也应能体现“未采集”，同时尽量保持默认（captured=true）输出不变。

## Security and Performance

- **Security:** `--no-params/--no-return` 将减少敏感数据泄露面（参数/返回值可能包含 token/PII），同时仍保留链路时序与线程信息用于排障。
- **Performance:** 本次修复允许额外开销（用户已确认）；但仍应避免在 captured=false 时做 snapshot/格式化，保证“关闭采集就不做昂贵工作”。

## Testing and Deployment

- **Testing:**
  - 单测覆盖：`--no-params` 仍有 `METHOD_ENTRY`；`--no-return` 仍有正常 `METHOD_EXIT`；captured 标记与输出占位正确。
  - 回归：确保不带 `--no-*` 的默认 watch 输出与行为保持一致（尽量不引入破坏性变化）。
- **Deployment:**
  - 无额外发布步骤；属于 core/bootstrap 同版本联动修复。

