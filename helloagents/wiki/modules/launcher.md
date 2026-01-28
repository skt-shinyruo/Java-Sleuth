# launcher

## Purpose
提供 CLI 入口、Attach 流程与交互会话。

## Module Overview
- **Responsibility:** JVM 选择、Attach、Socket 交互
- **Status:** ✅Stable
- **Last Updated:** 2026-01-28

## Specifications

### Requirement: 交互式诊断入口
**Module:** launcher
用户通过 CLI 与目标 JVM 内服务交互。

#### Scenario: 连接本地命令服务
前置：Agent 已启动命令服务  
- 连接 localhost:3658
- 逐行发送命令并读取响应

### Requirement: 握手协商与协议升级
**Module:** launcher
通过 HELLO/CONFIG 握手从服务端获取实际协议与能力，并在需要时升级到二进制帧通道。

#### Scenario: HELLO/CONFIG 握手
前置：连接建立并收到 welcome 行  
- 发送 `HELLO v=1 protocols=...`
- 读取 `CONFIG ... protocol=<legacy|framed|binary>`
- 若选择 binary：发送 `UPGRADE BINARY` 并切换到 BinaryFrame 通道

### Requirement: 分帧协议与流式支持
**Module:** launcher
支持 framed 模式、binary 模式与流式命令输出。

#### Scenario: framed 模式交互
前置：配置开启 framed  
- CMD/STREAM 前缀发送
- DATA/END/ERR 分帧读取

#### Scenario: binary 模式交互
前置：握手选择 binary  
- REQUEST/DATA/ERR/END 二进制帧读写
- 支持包含换行/长输出的严格分帧

### Requirement: 可选请求签名（security.mode=hmac）
**Module:** launcher
当启用 hmac 时，Launcher 会将命令封装为 `SIG ... cmd=<base64url>` 发送，以提供完整性校验与基础防重放。

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- agent
- command

## Change History
- 202601281100_init_kb (planned)
- 202601281207_sleuth_plugin_stream (history/2026-01/202601281207_sleuth_plugin_stream/) - framed/stream 协议支持
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - handshake + binary + 可选 SIG 签名
