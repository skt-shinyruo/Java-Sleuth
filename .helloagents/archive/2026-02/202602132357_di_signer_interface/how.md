# Technical Design: 显式构造注入（Security Managers）+ Launcher Signer 小接口

## Technical Solution

### Core Technologies
- Java 8
- Maven multi-module
- 面向接口编程（最小依赖面）
- 兼容性策略：保留 `getInstance()`，新增可注入构造函数/重载

### Implementation Key Points
1. **foundation：新增 `CommandSigner`**
   - 包位置：`com.javasleuth.security`
   - 方法：`sign(command, timestampMs, nonce, connId)`（与现有 SIG 协议字段一致）
   - 默认实现策略：
     - `RequestSecurityManager` 直接实现该接口（适配现有 `signCommand`）

2. **launcher：ProtocolClient 依赖 `CommandSigner`**
   - 将 `ProtocolClient` 内部字段从 `RequestSecurityManager` 替换为 `CommandSigner`
   - `connect(...)` 默认仍使用 `RequestSecurityManager.getInstance()` 作为 signer
   - 保留兼容重载（允许传 `RequestSecurityManager` / `CommandSigner`）

3. **foundation：security managers 增加可注入构造函数**
   - `RequestSecurityManager(ProductionConfig config, AuditLogger auditLogger)`
   - `AuthorizationManager(ProductionConfig config, AuditLogger auditLogger, AuthenticationManager authManager)`
   - `getInstance()` 仍可用：内部调用默认构造（保持旧行为）

4. **core：SleuthAgentCore composition root 收敛**
   - 优先显式构造 manager 实例：
     - `AuthenticationManager`（如仍使用单例则保持不变；本次优先收敛 Request/Authorization）
     - `RequestSecurityManager` / `AuthorizationManager` 使用构造注入，并共享同一套 config/audit/auth 依赖
   - 将这些实例注入 `CommandProcessor`（现有构造已支持）

## Architecture Design

```mermaid
flowchart TD
  L[launcher ProtocolClient] -->|depends on| S[CommandSigner]
  S -->|default impl| RSM[RequestSecurityManager]
  A[SleuthAgentCore] -->|new(...)| RSM
  A -->|new(...)| AM[AuthorizationManager]
  A --> CP[CommandProcessor]
```

## Architecture Decision ADR

### ADR-013: 客户端签名抽象为最小接口 CommandSigner
**Context:** 客户端仅需要“签名封装”，但当前依赖完整安全管理器，导致边界过宽与复用受限。  
**Decision:** 将客户端签名能力抽象为最小接口 `CommandSigner`，并让默认实现复用现有 `RequestSecurityManager` 的签名逻辑。  
**Rationale:**  
- 缩小 launcher/Web UI/headless client 的依赖面  
- 允许更换 signer 策略（无须修改协议客户端实现）  
**Alternatives:**  
- 方案 A：ProtocolClient 继续依赖 RequestSecurityManager → 拒绝原因：接口面过宽、单例隐式依赖难治理  
- 方案 B：复制一份 client-only signer 实现 → 拒绝原因：重复逻辑易漂移  
**Impact:**  
- ProtocolClient 构造/连接方法新增一个可选抽象参数；默认行为保持不变  

## Security and Performance
- **Security:** 显式注入使“依赖来源”更可审计；同时保留兼容路径避免破坏性升级。
- **Performance:** 接口抽象不引入额外开销；manager 实例化开销仅发生在 attach 启动阶段。

## Testing and Deployment
- **Testing:** `mvn test`；新增/调整单测覆盖：
  - ProtocolClient 使用自定义 signer 的编译/行为路径（最少 smoke）
  - 显式构造的 managers 在关键路径不依赖单例初始化顺序
- **Deployment:** 无产物变更。

