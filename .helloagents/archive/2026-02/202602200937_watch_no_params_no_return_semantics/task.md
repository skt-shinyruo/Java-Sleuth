# Task List: 修复 watch --no-params/--no-return 语义与插桩行为不一致

Directory: `helloagents/plan/202602200937_watch_no_params_no_return_semantics/`

---

## 1. enhancement / bootstrap（数据模型与拦截器）
- [√] 1.1 在 `bootstrap/src/main/java/com/javasleuth/data/WatchResult.java` 增加 `parametersCaptured/returnCaptured/(exceptionCaptured)` 字段与 getter/setter，并调整 `toString()` 在未采集时输出占位（仅影响 captured=false 场景），验证 why.md#requirement-watch-语义一致性修复（no-paramsno-return）-scenario---no-params-仍产生-method_entry
> Note: 本次仅引入 parametersCaptured/returnCaptured；未新增 exceptionCaptured（`--no-exception` 语义保持原实现，不在本修复范围内）。
- [√] 1.2 在 `bootstrap/src/main/java/com/javasleuth/monitor/WatchInterceptor.java` 写入 captured flags（并避免在 captured=false 时 snapshot），必要时增加新签名/桥接方法以兼容旧插桩字节码，验证 why.md#requirement-watch-语义一致性修复（no-paramsno-return）-scenario---no-return-仍产生正常-method_exit

## 2. enhancement / core（ASM 插桩语义修复）
- [√] 2.1 调整 `core/src/main/java/com/javasleuth/enhancement/WatchEnhancer.java`：入口/正常退出事件始终注入；`--no-params/--no-return` 仅影响传入数据与 captured 标记，验证 why.md#requirement-watch-语义一致性修复（no-paramsno-return）-scenario---no-params-仍产生-method_entry

## 3. command / core（输出语义对齐）
- [√] 3.1 调整 `core/src/main/java/com/javasleuth/command/impl/WatchCommand.java` 的 `--expr` 输出：当用户选择 `params/return` 且未采集时打印 `<not captured>`，避免静默缺失，验证 why.md#requirement-watch-语义一致性修复（no-paramsno-return）-scenario---no-return-仍产生正常-method_exit

## 4. Testing（回归与语义验证）
- [√] 4.1 新增或扩展单测（建议放在 `core/src/test/java/com/javasleuth/enhancement/`）：验证 `captureParameters=false` 仍产生 entry 事件、`captureReturn=false` 仍产生 exit 事件、captured flags 正确；同时确保原返回值/异常语义不被破坏
- [√] 4.2 执行 `mvn test`（至少覆盖 core/bootstrap 相关测试）

## 5. Security Check
- [√] 5.1 进行安全检查：确认不会在 `--no-params/--no-return` 时误采集敏感信息；确认新增占位输出不泄露对象引用/内容

## 6. Documentation Update（知识库同步）
- [√] 6.1（如需要）更新 `README.md` 或 `helloagents/wiki/modules/monitor.md`：明确 `--no-params/--no-return` 仅控制采集，不影响事件输出
- [√] 6.2 更新 `helloagents/CHANGELOG.md` 记录该修复
