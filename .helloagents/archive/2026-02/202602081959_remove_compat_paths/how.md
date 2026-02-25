# Technical Design: 协议兼容逻辑彻底移除（仅保留新协议）

## Technical Solution

### Core Technologies
- Java 8
- Socket `InputStream/OutputStream`
- framed 协议：`FrameCodec`（`DATA/ERR/END`）
- binary 协议：`BinaryFrameCodec`（upgrade 后切换 DataInputStream/DataOutputStream）
- 可选 HMAC：`RequestSecurityManager`

### Implementation Key Points
- **Server（CommandClientHandler）**
  - 握手（`HELLO/CONFIG`）为强制流程，协商协议仅限 `framed|binary`
  - 移除 legacy 相关提示与指标命名，协议错误统一返回“期望 CMD/STREAM”
- **Security（RequestSecurityManager）**
  - 修复 `verifyAndExtract` 编译错误，删除遗留的版本分支残片
  - `security.mode=hmac` 时：只允许单一 `SIG` 格式（必须包含 `sid`，且显式拒绝 `v` 字段）
  - `sid` 作为重放检测绑定 ID（nonce cache key 使用 `sid:nonce`）
- **Launcher（SleuthLauncher）**
  - 握手严格化：要求读取到 `CONFIG`，否则视为协议错误
  - binary upgrade 严格化：失败直接失败退出，不做 framed 回退（避免“协商 binary 但执行 framed”的错乱）
- **Docs / KB**
  - 更新 security/arch/changelog/history 索引，确保 SSOT 与代码一致

## Architecture Decision ADR

### ADR-007: HMAC 签名协议收敛为单一格式（禁用 v 字段）
**Context:**  \n历史上签名格式存在版本概念（v1/v2），且兼容分支易引入实现不一致与边界问题；在确认无旧实现/旧配置兼容需求后，应收敛为单一格式并 fail-fast。  \n
**Decision:**  \n当 `security.mode=hmac` 时，只接受不带版本字段的 `SIG` 格式（必须携带 `sid` 并绑定握手 `connId`），显式拒绝任何 `v` 字段与缺失 `sid` 的旧格式。  \n
**Rationale:**  \n降低协议歧义与维护成本，避免多版本分支造成实现缺口；在无 legacy 客户端前提下，破坏性收敛是更安全的方案。  \n
**Alternatives:**  \n- 方案 1：继续兼容 `SIG v=1/v=2` → 拒绝原因：增加分支与误用风险，与“不兼容旧实现”目标冲突  \n- 方案 2：完全关闭 HMAC → 拒绝原因：与安全能力目标冲突  \n
**Impact:**  \n- ✅ 签名解析/生成只有一种规范实现，避免版本错配  \n- ⚠️ 旧签名格式将被拒绝（预期行为）  \n

## Security and Performance
- **Security:** `SIG` 解析严格化（拒绝旧字段），重放检测绑定到 `sid`；不引入新的敏感信息输出
- **Performance:** 协议收敛后减少分支判断；nonce cache 仍按上限与时间窗口裁剪

## Testing and Deployment
- **Testing:** `mvn test`；覆盖签名校验与重放检测；覆盖协议握手/命令前缀校验（现有单测）
- **Deployment:** 如配置存在被拒绝的旧键，启动将 fail-fast；Launcher 与 Agent 需同时升级以匹配唯一 SIG 格式

