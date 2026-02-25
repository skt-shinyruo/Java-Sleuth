# Task List: legacy 文本协议响应边界稳定化（sync END marker）

Directory: `helloagents/history/2026-02/202602081451_legacy_text_end_marker_sync/`

---

## 1. command / protocol
- [√] 1.1 在 `core/src/main/java/com/javasleuth/command/server/protocol/CommandRequestExecutor.java` 中为 legacy（`framedRequested=false`）的 **sync 成功回包**追加 `END` marker（受 `protocol.text.end.marker.enabled` 控制），verify why.md#requirement-legacy-text-response-boundary-req-legacy-end-scenario-sync-output-contains-newlines-scn-legacy-sync-multiline
- [√] 1.2 在 `core/src/main/java/com/javasleuth/command/server/protocol/CommandRequestExecutor.java` 中为 legacy 的 **sync 失败回包与兜底异常**追加 `END` marker（避免对 framed/binary double-END），verify why.md#requirement-legacy-text-response-boundary-req-legacy-end-scenario-sync-error-also-has-deterministic-end-scn-legacy-sync-error-end
- [√] 1.3（可选增强）评估并实现对输出中“整行 `END`”的保留字处理（转义/替换/提示），避免与终止行冲突，verify why.md#change-content

## 2. launcher
- [√] 2.1 在 `core/src/main/java/com/javasleuth/launcher/SleuthLauncher.java` legacy 模式读取逻辑中：优先按 `END` 结束读取，保留对旧 Agent 的短超时 fallback，并确保不会把 `END` 当作业务输出打印，verify why.md#requirement-legacy-text-response-boundary-req-legacy-end-scenario-backward-compatibility-with-older-agents-scn-legacy-compat-old

## 3. Security Check
- [√] 3.1 执行协议安全检查：确认不存在把异常堆栈/控制字符写入 legacy 响应导致协议控制行混淆；确认 `protocol.text.end.marker.enabled=false` 时仍不会出现“残留 END”写回，verify how.md#security-and-performance

## 4. Documentation Update
- [√] 4.1 更新 `core/src/main/resources/sleuth-default.properties` 中 `protocol.text.end.marker.enabled` 的注释，明确其覆盖 legacy sync 响应边界语义，并同步知识库 `helloagents/wiki/modules/command.md` 的协议说明，verify why.md#change-content

## 5. Testing
- [√] 5.1 新增/扩展单测覆盖 legacy sync 多行输出场景，建议新增 `core/src/test/java/com/javasleuth/command/LegacyTextProtocolEndMarkerTest.java`：断言响应以 `END` 结束且不会产生跨命令错位，verify why.md#requirement-legacy-text-response-boundary-req-legacy-end-scenario-sync-output-contains-newlines-scn-legacy-sync-multiline
- [√] 5.2 新增单测覆盖开关关闭保持旧行为（不追加 `END`），并在客户端侧仍可使用 fallback 读完单行输出，verify how.md#testing-and-deployment
