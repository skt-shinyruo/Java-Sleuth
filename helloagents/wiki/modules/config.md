# config

## Purpose
统一加载并管理运行时配置。

## Module Overview
- **Responsibility:** 默认配置、外部配置、系统属性覆盖
- **Status:** ✅Stable
- **Last Updated:** 2026-01-29

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
- `server.max.connections`：并发连接上限（默认 10）
- `protocol.handshake.enabled`：默认 true
- `protocol.mode`：legacy|framed|binary
- `protocol.text.max.line.bytes`：文本协议单行最大字节数上限
- `security.mode`：off|hmac（默认 off）
- `security.anonymous.viewer`：默认 false（要求先 auth）
- `security.hmac.*`：HMAC 签名与防重放参数
- `performance.command.timeout`：命令执行超时
- `performance.maintenance.force_gc`：维护线程是否强制 `System.gc()`（默认 false）

### Requirement: 运行时覆盖（Runtime Overrides）
**Module:** config
支持通过命令在运行时覆写部分配置项（优先级高于默认配置与外部文件），用于临时调试与回滚。

#### Scenario: config set/get 生效
前置：已连接并具备足够权限（建议 ADMIN）  
- `config set <key> <value>` 写入运行时覆盖  
- 读取配置时优先使用运行时覆盖（其次系统属性，再其次文件配置）  
- 对敏感 key 的 value 输出会自动脱敏

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
N/A

## Change History
- 202601281100_init_kb (planned)
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - 新增 bind/handshake/security.mode 配置项
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - 安全默认/资源治理/运行时覆写扩展与文档一致性修复
