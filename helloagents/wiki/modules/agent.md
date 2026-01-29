# agent

## Purpose
提供 Java Agent 入口与生命周期管理。

## Module Overview
- **Responsibility:** agentmain/premain 入口、Transformer 注册、命令服务启动
- **Status:** ✅Stable
- **Last Updated:** 2026-01-29

## Specifications

### Requirement: Agent Attach 启动
**Module:** agent
在目标 JVM 内启动诊断服务并注册字节码增强。

#### Scenario: Attach 成功后进入交互
前置：Launcher 通过 Attach API 注入 Agent  
- 解析 agentArgs 并注入 `sleuth.*` 系统属性（包含 `sleuth.config.file`）
- 启动 CommandProcessor
- 注册 SleuthClassFileTransformer

### Requirement: 生命周期不阻塞 JVM 退出
**Module:** agent
Agent 的命令处理线程应避免阻塞目标 JVM 正常退出（尤其是当应用仅剩 daemon 线程时）。

#### Scenario: 应用退出时不被命令线程阻塞
前置：Agent 已 attach 并启动 CommandProcessor  
- 命令处理线程使用 daemon 线程运行  
- shutdown 路径具备空指针保护，避免异常导致残留线程/资源

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- command
- enhancement

## Change History
- 202601281100_init_kb (planned)
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - agentArgs 注入与配置一致性
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - 命令线程 daemon 化与 shutdown 健壮性
