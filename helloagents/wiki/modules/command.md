# command

## Purpose
定义命令接口、注册与执行链路。

## Module Overview
- **Responsibility:** 命令解析、校验、执行、输出
- **Status:** ✅Stable
- **Last Updated:** 2026-01-28

## Specifications

### Requirement: 命令执行链路
**Module:** command
从输入到执行的完整处理流程。

#### Scenario: 命令被解析并执行
前置：客户端已连接  
- 输入校验
- 命令执行
- 结果清洗与返回

### Requirement: 插件化命令与分帧协议
**Module:** command
支持插件加载与分帧/流式输出，保持兼容模式；并支持严格二进制帧通道。

#### Scenario: 插件命令加载
前置：CommandProcessor 启动  
- SPI/插件目录加载
- 冲突策略可配置
- 插件命令动态注册到 AuthorizationManager（避免 unknown command 被拒绝）

#### Scenario: 分帧与流式输出
前置：客户端使用 framed 模式  
- DATA/END/ERR 分帧输出
- watch/trace 可流式推送

#### Scenario: 严格二进制帧输出
前置：握手选择 binary 模式  
- REQUEST/DATA/ERR/END 二进制帧读写
- Payload 支持任意换行与长输出（长度前缀）

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- security
- monitoring
- util
- enhancement

## Change History
- 202601281100_init_kb (planned)
- 202601281207_sleuth_plugin_stream (history/2026-01/202601281207_sleuth_plugin_stream/) - 插件化命令与分帧协议
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - 握手协商 + 严格二进制帧 + 插件授权治理
