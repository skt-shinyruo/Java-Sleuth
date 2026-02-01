# monitoring

## Purpose
系统指标采集与健康检查。

## Module Overview
- **Responsibility:** MetricsCollector 与 MBean 暴露
- **Status:** ✅Stable
- **Last Updated:** 2026-02-01

## Specifications

### Requirement: 运行时指标输出
**Module:** monitoring
提供 metrics/health/status 等命令的数据来源。

#### Scenario: 采集与输出指标
前置：命令请求指标  
- 读取 JVM 指标
- 统计命令/会话指标
- 统计协议/插件指标（handshake/binary upgrade/plugin provider/command）

### Requirement: 性能/健康日志默认克制（避免污染 stdout/stderr）
**Module:** monitoring / config
生产环境不应默认把性能/健康告警写到 stdout/stderr（会干扰业务日志管道），保持可配置、可观测但不“强制刷屏”。

#### Scenario: 仅在显式开启时输出周期性性能告警
前置：`logging.performance.enabled=true`  
- 周期性采集会输出性能/健康告警（例如高内存/高错误率/慢命令）
- 默认关闭：仅通过命令读取指标，不主动写 stdout/stderr

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- util

## Change History
- 202601281100_init_kb (planned)
- 202601281301_sleuth_handshake_secure_frames (history/2026-01/202601281301_sleuth_handshake_secure_frames/) - 协议/插件/安全指标补齐
- 202602011706_core_fixes_java8_jad_session_regex_trace (history/2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/) - 性能/健康日志默认关闭（可配置开启）
