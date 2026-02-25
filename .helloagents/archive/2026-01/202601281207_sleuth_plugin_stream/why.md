# Change Proposal: 命令插件化与流式诊断（细化版）

## Requirement Background
当前系统存在以下结构性问题：端口配置与客户端硬编码不一致、响应边界不稳定、认证授权未接入主链路、类级单一 Enhancer 导致多会话冲突、watch/trace 高频事件无背压风险、命令解析不支持引号/转义，且命令注册缺少扩展机制。为保持现有 CLI 体验并增强可扩展性，需要引入插件化命令与稳定的流式/分帧协议，同时统一命令执行管线与安全策略。

## Change Content
1. 引入插件化命令加载机制（SPI + 可选插件目录），实现内置命令与插件并存
2. 建立统一命令执行管线（解析→校验→鉴权→审计→缓存→执行→输出清洗）
3. 引入分帧协议与流式输出通道，解决响应截断与长输出粘包
4. 改造 Enhancer 管理为“同类多增强可叠加”，避免会话互相覆盖
5. 为 watch/trace 引入队列上限、丢弃/采样策略，防止内存膨胀

## Impact Scope
- **Modules:** launcher, command, enhancement, monitor, security, config, util, data
- **Files:** `src/main/java/com/javasleuth/launcher/*`, `src/main/java/com/javasleuth/command/*`, `src/main/java/com/javasleuth/command/impl/*`, `src/main/java/com/javasleuth/security/*`, `src/main/java/com/javasleuth/monitor/*`, `src/main/java/com/javasleuth/enhancement/*`, `src/main/java/com/javasleuth/config/*`
- **APIs:** 内部 Socket 文本协议（需兼容旧协议）
- **Data:** 诊断事件流分帧格式与流式会话模型

## Core Scenarios

### Requirement: Pluginized Command System
**Module:** command
支持在不修改核心代码的情况下加载/卸载命令插件。

#### Scenario: Load Providers at Startup
条件：CommandProcessor 启动  
- 内置命令正常加载
- SPI/插件目录可扩展
- 重名命令有优先级与冲突策略

### Requirement: Framed & Streaming Protocol
**Module:** command
提供稳定的响应分帧与流式输出，兼容旧协议。

#### Scenario: Stream Watch/Trace Events
条件：执行 watch/trace  
- 建立流式输出通道
- 支持超时与中止
- 不影响旧 CLI 单次响应

### Requirement: Unified Execution Pipeline
**Module:** command
统一命令执行链路与策略，降低安全与一致性风险。

#### Scenario: Execute with Pipeline
条件：客户端发送命令  
- 输入校验与命令白名单
- 角色权限校验与审计
- 可选缓存/超时控制

### Requirement: Session Auth & Authorization
**Module:** security
在 Socket 会话中建立角色身份并强制鉴权。

#### Scenario: Authenticate and Enforce
条件：新连接建立  
- 默认角色为 viewer
- 通过 auth/login 命令提升权限
- 高危命令必须授权

### Requirement: Enhancer Isolation & Backpressure
**Module:** enhancement / monitor
允许多会话叠加增强，并对高频事件进行背压控制。

#### Scenario: Concurrent Watches on Same Class
条件：多个 watch/trace 同时作用于同一类  
- 增强可叠加，不互相覆盖
- 退出会话只移除对应 enhancer

#### Scenario: High-Frequency Event Burst
条件：高频方法触发  
- 队列上限生效
- 丢弃/采样策略可配置

## Risk Assessment
- **Risk:** 新协议与旧 CLI 不兼容  
  **Mitigation:** 兼容模式默认启用，分帧协议可通过配置开启
- **Risk:** 插件注入不可信命令  
  **Mitigation:** 插件目录白名单与签名/禁用开关；命令权限控制
- **Risk:** 流式输出高频事件导致压力  
  **Mitigation:** 队列上限 + 丢弃/采样策略 + 速率限制

