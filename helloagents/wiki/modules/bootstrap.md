# bootstrap

## Purpose
承载 **必须 bootstrap 可见** 的 spy/bridge 类（JDK-only），供增强代码在业务线程中直接调用。

## Module Overview
- **Responsibility:** monitor 拦截器、共享数据模型、轻量共享工具（值快照/环形缓冲/产物定位/agentArgs 落地）
- **Status:** ✅Stable
- **Last Updated:** 2026-02-13
- **Build Module:** bootstrap（`java-sleuth-bootstrap`）

## Specifications

### Requirement: Bootstrap 暴露面最小化
**Module:** bootstrap / agent
被 append 到 bootstrap 搜索路径的 jar 仅包含增强必需桥接类，避免把 config/security/protocol 等能力提升为 bootstrap 可见。

#### Scenario: 仅桥接类可见
前置：attach 注入成功  
- 业务代码可见 `com.javasleuth.monitor/*`、`com.javasleuth.data/*` 等桥接类型
- 业务代码不可见 `foundation` 下的 config/security/command-protocol 等实现细节

### Requirement: 规则 SSOT（避免漂移）
**Module:** bootstrap
jar 定位与 agentArgs 落地规则统一，供 launcher/agent/core 复用。

#### Scenario: 产物定位与参数落地一致
前置：发布包 / IDE / 任意 cwd  
- `JarLocator` 以 Manifest marker 定位 `agent`/`core` 产物，并支持 sysprop/env 覆盖
- `AgentArgsApplier` 统一 `agentArgs` 解析并写入 `sleuth.*` sysprop（bootstrap/core 共用）

## API Interfaces
N/A

## Data Models
- WatchResult / TraceResult / TtRecord / StackTraceResult

## Notes
- `bootstrap` 模块必须保持 **无第三方依赖**（JDK-only），避免 bootstrap 可见范围引入依赖碰撞风险。
- `monitor` 拦截器只读取 `sleuth.monitoring.*` sysprop（带默认值）。core 启动阶段会 best-effort 将 `ProductionConfig` effective 值同步到 sysprop（未显式覆盖时补齐）。

## Dependencies
N/A

## Change History
- 202602132045_bootstrap_boundary_cleanup (history/2026-02/202602132045_bootstrap_boundary_cleanup/) - bootstrap 模块引入与边界收敛
