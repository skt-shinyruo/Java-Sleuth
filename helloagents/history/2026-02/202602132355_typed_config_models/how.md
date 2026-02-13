# Technical Design: 强类型配置模型（Typed Config Models）

## Technical Solution

### Core Technologies
- Java 8（Maven 多模块：foundation/core/launcher）
- `Properties` + `ConfigLoader`（默认资源 + 外部文件 + system properties 覆盖）
- `ConfigView` / `ConfigSnapshot`（提供读取优先级与来源可观测）

### Implementation Key Points
1. **新增强类型配置模型（不可变对象）**
   - 在 `foundation` 新增 `com.javasleuth.config.model` 包（示例）：
     - `ServerConfig`：bind address、port、max connections、timeouts 等
     - `ProtocolConfig`：mode、streaming、frameMaxPayload、textMaxLineBytes（含默认计算规则）
     - `SecurityConfig`：security.mode、authorization、hmac 自举参数等
   - 强类型对象只暴露字段/方法，不暴露字符串 key。

2. **集中解析与校验（Parser/Factory）**
   - 新增 `SleuthConfig`（聚合多个子配置）+ `SleuthConfigParser`：
     - 输入：`ConfigView`（建议传入 `ConfigSnapshot`）
     - 输出：`SleuthConfig`（含校验后的强类型值）
   - 校验策略：
     - enum：如 `protocol.mode` 仅允许 framed|binary
     - range：如 queue capacity、timeout、payload 等必须为正并限制上限
     - 空值：必要 key 缺失时提供一致的兜底策略（优先依赖默认资源；否则使用 fallback 默认；仍为空则 fail-fast）

3. **边界解析一次，核心链路只依赖强类型字段**
   - server 侧：在连接建立/握手前创建 `ConfigSnapshot` 并解析 `SleuthConfig`，避免 IO loop 中反复解析与散落 key。
   - launcher 侧：在连接前解析 `SleuthConfig`，并复用相同的默认计算规则（例如 `textMaxLineBytes` 的默认计算与 `frameMaxPayload` 的关联）。

4. **默认值一致性治理（SSOT + 自动化校验）**
   - 明确 SSOT：`foundation/src/main/resources/sleuth-default.properties`
   - `DefaultConfigFallback` 作为资源缺失时兜底，不再“手写散落默认值”：
     - 方案：抽取一个 `SleuthDefaults`（或 `SleuthConfigParser#applyDefaults(Properties)`）集中写入默认键值
     - `DefaultConfigFallback` 仅调用该集中默认入口
   - 新增单测：校验默认资源包含强类型模型需要的 key；校验关键默认值/解析规则一致。

## Architecture Design

```mermaid
flowchart TD
  A[ConfigLoader: load defaults + file + sysprops] --> B[ProductionConfig / Properties]
  B --> C[ConfigSnapshot (optional, request/session scope)]
  C --> D[SleuthConfigParser]
  D --> E[SleuthConfig (Server/Protocol/Security...)]
  E --> F[HandshakeNegotiator / CommandClientHandler / Launcher]
```

## Architecture Decision ADR

### ADR-010: 引入强类型配置模型，替代散落字符串 key + 默认值
**Context:** 当前 `ConfigView#getXxx(String key, default)` 易导致 key/默认值分散与漂移，协议/安全配置风险更高。  
**Decision:** 在 `foundation` 增加 `SleuthConfig` 强类型配置对象与集中解析校验入口；关键链路在边界处解析一次并注入使用。  
**Rationale:** 通过“类型聚合 + 单点默认/校验 + 测试约束”降低维护成本与安全/协议配置漂移风险。  
**Alternatives:**  
- Solution 1：仅做 `ConfigKeys` 常量化 + 默认值集中（改动较小但仍保留大量 `getXxx(key)` 调用）  
- Solution 3：schema 驱动 + 代码生成（长期最稳，但工程投入更大）  
**Impact:** 构造注入链路会涉及多文件修改；需要提供渐进迁移策略并用单测保护行为。

## Security and Performance
- **Security:**
  - 强类型模型解析时禁止输出敏感值（secret/password 等），日志仅输出 key 与来源/摘要
  - 对安全相关配置（如 `security.mode`）做严格枚举校验，避免“非法值被默默归一化”
- **Performance:**
  - 在连接/会话边界解析一次，避免在每次读写循环里反复读取/解析 Properties
  - 保留 `ConfigSnapshot` 作为一致性边界，减少同一请求内的配置混合状态风险

## Testing and Deployment
- **Testing:**
  - 增加强类型解析单测（默认值、覆盖优先级、非法值校验）
  - 增加 server/launcher 关键协议参数一致性测试（`frameMaxPayload` 与 `textMaxLineBytes` 的默认计算规则一致）
  - 增加默认资源一致性测试（`sleuth-default.properties` 包含并匹配强类型模型所需配置项）
- **Deployment:**
  - 不改动现有配置 key（保持向后兼容）
  - 以渐进迁移方式上线：优先迁移协议/握手与连接边界，再迁移非关键路径

