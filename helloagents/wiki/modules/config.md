# config

## Purpose
统一加载并管理运行时配置。

## Module Overview
- **Responsibility:** 默认配置、外部配置、系统属性覆盖
- **Status:** ✅Stable
- **Last Updated:** 2026-01-28

## Specifications

### Requirement: 配置加载优先级
**Module:** config
默认配置 → 外部文件 → 系统属性覆盖（支持运行时读取 `-Dsleuth.<key>`）。

#### Scenario: 启动时读取配置
前置：系统启动  
- 读取 /sleuth-default.properties
- 读取 sleuth.properties（若存在，或通过 `-Dsleuth.config.file=/path/to/sleuth.properties` 指定）
- 读取 -Dsleuth.* 覆盖

### Requirement: 安全/协议新增配置项
**Module:** config
支持以下新增配置项（均可通过 `sleuth.properties` 或 `-Dsleuth.*` 设置）：
- `server.bind.address`：默认 127.0.0.1
- `protocol.handshake.enabled`：默认 true
- `protocol.mode`：legacy|framed|binary
- `security.mode`：off|hmac|tls（默认 off）
- `security.hmac.*`：HMAC 签名与防重放参数

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
N/A

## Change History
- 202601281100_init_kb (planned)
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - 新增 bind/handshake/security.mode 配置项
