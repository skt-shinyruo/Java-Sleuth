# Technical Design: legacy 文本协议响应边界稳定化（sync END marker）

## Technical Solution

### Core Technologies
- Java 8
- Socket `InputStream/OutputStream` 行协议（legacy）与分帧协议（framed/binary）

### Implementation Key Points
- **服务端（Agent）**：在 legacy（`framedRequested=false`）的 sync 执行路径中：
  - 成功路径：`sendData(...)` 之后追加 `sendLegacyEndMarker()`（受 `protocol.text.end.marker.enabled` 控制）
  - 失败路径：`sendError(...)` 之后追加 `sendLegacyEndMarker()`（同上）
  - 兜底异常：`catch(Exception)` 中写入错误后同样追加 `sendLegacyEndMarker()`（同上）
  - framed/binary 路径保持原有 END frame 语义，避免 double-END
- **客户端（Launcher）**：legacy 读取逻辑以 `END` marker 为主：
  - 新 Agent：稳定按 `END` 结束读取，避免短超时猜测导致的错位
  - 旧 Agent：仍允许短超时 fallback（兼容旧行为）
- **输出净化策略（InputValidator）**：不强行移除 `\r/\n`，而是将“边界稳定性”职责下沉到协议层（`END`）；必要时可在 legacy 模式下新增“保留字/整行 END”处理（可选增强）

## Architecture Design
N/A（协议语义增强，保持现有 text/framed/binary 三轨并行架构）

## Architecture Decision ADR

### ADR-004: legacy sync 响应以 END marker 作为确定边界
**Context:**  
legacy text 模式缺少显式 end frame，客户端常通过短超时猜测结束；当响应可能包含多行（包含换行或截断提示带换行）时，边界不确定会导致“残留行串入下一条命令响应”的错位问题。

**Decision:**  
在 legacy text 模式下，服务端对 sync 响应追加单行 `END` marker 作为响应终止；客户端优先按 `END` 结束读取，并保留对旧 Agent 的超时 fallback。

**Rationale:**  
在不引入新的 framing/length-prefix 的前提下，以最小实现成本为 legacy text 提供确定边界，消除对超时猜测的依赖；同时不影响 framed/binary 既有语义。

**Alternatives:**  
- 方案 1：对 legacy 输出强制单行化（替换/转义 `\r/\n`） → 拒绝原因：会改变输出可读性，且对 framed/binary 不是必要约束  
- 方案 2：在 `Utf8LineCodec.writeLine` 全局收口单行化 → 拒绝原因：影响面过大，可能改变非 legacy 的输出行为  
- 方案 3：完全废弃 legacy，强制 framed/binary → 拒绝原因：存在存量兼容需求，需渐进迁移

**Impact:**  
- ✅ legacy 响应边界确定，客户端无需靠超时猜测  
- ⚠️ 旧 legacy 客户端若未实现 `END` 读取可能出现兼容性问题（需明确升级策略/提供开关）

## API Design
N/A

## Data Model
N/A

## Security and Performance
- **Security:** 保持输出净化策略不回显控制字符（除 `\r/\n/\t` 的既有策略），并避免将协议控制行与业务输出混淆；必要时对输出中的整行 `END` 做保留字处理
- **Performance:** 避免 legacy 客户端依赖短超时循环猜测结束，降低误判与重试带来的额外 I/O；对 framed/binary 无额外开销

## Testing and Deployment
- **Testing:** 增加单测覆盖 legacy sync 多行输出与错误输出的 `END` 终止行为；同时覆盖“开关关闭时保持旧行为”
- **Deployment:** 建议默认保持 `protocol.mode=framed`；legacy 模式仅作为兼容通道使用，并在变更日志中明确 END marker 语义扩展

