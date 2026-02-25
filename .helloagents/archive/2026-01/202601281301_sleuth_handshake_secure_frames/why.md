# Change Proposal: 握手协商 + 严格帧协议 + 插件授权治理

## Requirement Background
当前系统仍存在 5 类关键问题：
1) Launcher 与目标 JVM 内 Agent 的配置来源不一致（工作目录/配置文件路径不同），导致端口/协议模式可能不匹配；
2) framed 协议仍基于行读取，长度字段不严格，边界与换行场景不可靠；
3) 插件命令可加载但无法在静态授权模型下使用，插件能力与安全策略存在结构冲突；
4) 通信链路缺少安全传输与防重放策略，默认口令风险高；
5) 多 Enhancer/流式输出缺少自保护与可观测性，测试覆盖不足。

为提升生产可用性与可扩展性，需要引入跨进程握手协商、严格分帧协议、插件权限治理与安全边界，并补齐指标与测试。

## Change Content
1. 设计 Launcher↔Agent 握手协议（HELLO/CONFIG），统一协商：端口、协议版本、是否流式、最大帧大小、安全模式
2. 引入严格长度前缀的二进制帧协议（替代 readLine 分帧），支持 DATA/ERR/END 以及流式多帧
3. 插件命令权限治理：插件声明权限 → 管理侧配置/白名单审核 → 授权模块动态加载（而非静态 hardcode）
4. 安全边界：引入共享密钥 + 签名/nonce 防重放（可选 TLS）；移除/禁用默认口令，提供部署期初始化流程
5. 自保护与可观测性：增强失败回退、采样/限流指标化、协议错误统计、回归测试覆盖（并发会话、协议边界、权限矩阵）

## Impact Scope
- **Modules:** launcher, agent, command, security, enhancement, monitor, monitoring, config
- **Files:** `src/main/java/com/javasleuth/launcher/*`, `src/main/java/com/javasleuth/agent/*`, `src/main/java/com/javasleuth/command/*`, `src/main/java/com/javasleuth/security/*`, `src/main/java/com/javasleuth/enhancement/*`, `src/main/java/com/javasleuth/monitor/*`, `src/main/java/com/javasleuth/monitoring/*`, `src/main/java/com/javasleuth/config/*`
- **APIs:** TCP 控制面协议（新增握手与二进制帧；保留兼容模式）
- **Data:** 插件权限元数据、会话安全上下文

## Core Scenarios

### Requirement: Cross-Process Configuration Consistency
**Module:** launcher / agent / config
保证 Launcher 与 Agent 启动参数一致，避免端口/协议错配。

#### Scenario: HELLO/CONFIG Handshake
条件：Launcher 连接 Agent  
- Launcher 发送 HELLO（含支持协议版本与期望能力）
- Agent 返回 CONFIG（实际端口、协议版本、帧限制、安全能力）
- Launcher 依据 CONFIG 切换协议/能力

### Requirement: Strict Framing Protocol
**Module:** command
用严格长度前缀的帧协议保证响应边界与二进制安全。

#### Scenario: Long Output and Newlines
条件：命令输出包含多行/超长  
- 输出按帧拆分，不依赖 readLine
- 读取按 length 精确消费，避免截断/粘包

### Requirement: Plugin Authorization Governance
**Module:** command / security
插件命令可被安全地加载与授权使用。

#### Scenario: Plugin Command with Required Role
条件：加载插件命令  
- 插件声明 requiredRole/dangerous/ratelimit
- 服务端根据配置决定是否允许注册与执行
- 审计记录来源与授权决策

### Requirement: Secure Transport & Replay Protection
**Module:** security
默认安全边界清晰，可选 TLS，至少支持共享密钥签名与 nonce 防重放。

#### Scenario: Signed Requests
条件：安全模式开启  
- 每条请求含 timestamp + nonce + signature
- 服务端校验并拒绝重放

### Requirement: Self-Protection & Observability
**Module:** enhancement / monitoring / monitor
增强/流式具备自保护并可观测。

#### Scenario: Enhancer Failure Rollback
条件：某 enhancer 插桩失败  
- 自动禁用该 enhancer 并回退 retransform
- 记录指标与审计事件

#### Scenario: Load Shedding
条件：高频事件流  
- 队列限额 + 丢弃/采样策略生效
- 指标可观测：drop 计数、采样率、延迟

## Risk Assessment
- **Risk:** 协议升级影响兼容性  
  **Mitigation:** 维持 legacy 模式；握手协商以能力降级
- **Risk:** 安全引入运维成本  
  **Mitigation:** 默认关闭 TLS、默认启用签名；提供清晰初始化与轮换流程
- **Risk:** 插件治理复杂  
  **Mitigation:** 先实现“声明 + 白名单 + 审计”，再迭代更强策略

