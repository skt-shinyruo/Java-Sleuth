# agent

## Purpose
提供 Java Agent 入口与生命周期管理。

## Module Overview
- **Responsibility:** agentmain/premain 入口、Transformer 注册、命令服务启动
- **Status:** ✅Stable
- **Last Updated:** 2026-01-28

## Specifications

### Requirement: Agent Attach 启动
**Module:** agent
在目标 JVM 内启动诊断服务并注册字节码增强。

#### Scenario: Attach 成功后进入交互
前置：Launcher 通过 Attach API 注入 Agent  
- 解析 agentArgs 并注入 `sleuth.*` 系统属性（包含 `sleuth.config.file`）
- 启动 CommandProcessor
- 注册 SleuthClassFileTransformer

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
