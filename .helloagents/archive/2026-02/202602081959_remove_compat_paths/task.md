# Task List: 协议兼容逻辑彻底移除（仅保留新协议）

Directory: `helloagents/history/2026-02/202602081959_remove_compat_paths/`

---

## 1. Security（SIG 收敛 + 编译修复）
- [√] 1.1 修复 `RequestSecurityManager.verifyAndExtract` 的编译错误；收敛为单一 SIG 格式（拒绝 `v` 字段、强制 `sid` 绑定），验证 why.md#requirement-single-sig-format-req-sig-single
- [√] 1.2 清理 `RequestSecurityManager.signCommand` 的历史兼容分支（移除不可达/旧格式逻辑），验证 why.md#requirement-single-sig-format-req-sig-single
- [√] 1.3 更新/调整单测 `RequestSecurityManagerTest` 命名与断言（移除 v2 表述），验证 why.md#requirement-single-sig-format-req-sig-single

## 2. Command Protocol（去除 legacy 语义残留）
- [√] 2.1 更新 `CommandClientHandler`：移除 legacy 文案/指标名，协议错误只提示期望 `CMD/STREAM`，验证 why.md#requirement-new-protocol-only-req-proto-only
- [√] 2.2 补齐 binary 协议实现（`BinaryFrame/BinaryFrameCodec/BinaryClientProtocolHandler`），确保 framed/binary 双协议路径可编译运行

## 3. Launcher（严格握手/升级，不做回退）
- [√] 3.1 更新 `SleuthLauncher`：握手必须收到 `CONFIG`；binary upgrade 失败直接失败退出（不回退 framed），验证 why.md#requirement-new-protocol-only-req-proto-only

## 4. Security Check
- [√] 4.1 安全自检：确认无明文 secret/token 输出；确认协议错误信息不泄露敏感数据（G9）

## 5. Documentation Update（SSOT 同步）
- [√] 5.1 更新 `helloagents/wiki/modules/security.md`：SIG 格式说明收敛为单一规范（移除 v2 表述）
- [√] 5.2 更新 `helloagents/wiki/arch.md`：ADR-006 文案同步 + 追加 ADR-007（或索引），与代码一致
- [√] 5.3 更新 `helloagents/CHANGELOG.md`：修正 “SIG v=2” 描述为单一 SIG 格式
- [√] 5.4 更新 `helloagents/history/index.md`：补齐 `202602081900_enforce_new_protocol_only` 索引，并登记本次变更包
- [√] 5.5 更新 `helloagents/wiki/api.md`：握手强制 + SIG 格式补齐 `sid` 并禁用 `v`

## 6. Testing
- [√] 6.1 执行 `mvn test` 并修复失败项
