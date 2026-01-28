# monitoring

## Purpose
系统指标采集与健康检查。

## Module Overview
- **Responsibility:** MetricsCollector 与 MBean 暴露
- **Status:** ✅Stable
- **Last Updated:** 2026-01-28

## Specifications

### Requirement: 运行时指标输出
**Module:** monitoring
提供 metrics/health/status 等命令的数据来源。

#### Scenario: 采集与输出指标
前置：命令请求指标  
- 读取 JVM 指标
- 统计命令/会话指标
- 统计协议/插件指标（handshake/binary upgrade/plugin provider/command）

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- util

## Change History
- 202601281100_init_kb (planned)
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - 协议/插件/安全指标补齐
