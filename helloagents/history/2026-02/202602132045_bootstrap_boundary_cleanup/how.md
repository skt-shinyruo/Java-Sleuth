# Technical Design: Bootstrap 边界收敛与重复实现去重

## Technical Solution

### Core Technologies
- Java 8
- Java Instrumentation（bootstrap append + isolated ClassLoader）
- Maven 多模块（reactor build）

### Implementation Key Points
1. **新增 `bootstrap` 模块（JDK-only）**
   - 目标：成为 “bootstrap 可见桥接类”的唯一承载模块（spy/bridge）。
   - 将增强代码调用点依赖的类型迁移至该模块，保持 FQCN 不变。

2. **拦截器配置来源收敛**
   - `com.javasleuth.monitor.*` 不再直接依赖 `ProductionConfig`。
   - 以 System properties 作为 bootstrap 侧唯一配置读取来源（例如 `sleuth.monitoring.*`）。
   - `core` 在启动时对关键监控项进行 sysprop 同步（若 sysprop 未显式覆盖，则写入 effective config 值）。

3. **SSOT 去重**
   - `JarLocator` 作为 jar marker/定位唯一实现，bootstrap/launcher/agent 统一使用。
   - `AgentArgs` 解析与 sysprop 落地统一实现，bootstrap/core 复用。

## Architecture Design

```mermaid
flowchart TD
    L[launcher] -->|Attach API| A[agent (bootstrap entry)]
    A -->|appendToBootstrapClassLoaderSearch| B[bootstrap module classes]
    A -->|URLClassLoader parent=null| C[core fat-jar]
    C --> F[foundation (non-bootstrap JDK-only)]

    subgraph Bootstrap Visible
      B --> M[monitor interceptors]
      B --> D[data models]
      B --> U[snapshot utils]
    end
```

## Architecture Decision ADR

### ADR-1: 引入独立 `bootstrap` 模块承载 spy/bridge（Recommended）
**Context:** `agent` 作为 fat-jar 且 append 到 bootstrap，导致 `foundation` 被整体提升为 bootstrap 可见；同时 jar 定位与 agentArgs 规则重复实现存在漂移风险。  
**Decision:** 新增 Maven 模块 `bootstrap`，承载所有必须 bootstrap 可见的桥接类；`agent` 仅依赖 `bootstrap`（不依赖 `foundation`）；jar 定位与 agentArgs 规则收敛到公共工具类。  
**Rationale:** 将 bootstrap 暴露面收敛为“增强必需最小集合”，降低误用/碰撞/演进耦合；减少重复实现漂移风险。  
**Alternatives:**
- 方案 A：继续使用 `foundation`，通过 assembly filter 排除非必需包 → Rejection reason: 维护复杂、易漏、对默认 assembly descriptor 侵入大。
- 方案 B：把 bootstrap spy 单独打成一个 jar 并 append 两个 jar → Rejection reason: 交付物与定位复杂度上升，对现有 JarLocator/Attach 流程改动更大。
**Impact:** 模块与构建结构发生变化，需要同步调整 poms、文档与回归测试；但运行时边界更清晰、长期维护成本更低。

## Security and Performance
- **Security:**
  - 降低 bootstrap 可见面，减少“被业务代码意外引用/碰撞”的风险面。
  - `agentArgs` 落地规则统一，避免安全参数在不同入口解析不一致。
- **Performance:**
  - 拦截器避免加载完整 `ProductionConfig`，减少 bootstrap 侧类加载与潜在 I/O 风险。
  - sysprop 读取开销极低，且仅在监控启用时走热路径。

## Testing and Deployment
- **Testing:**
  - `mvn test`（reactor）全量回归
  - 重点关注：watch/trace/monitor/tt/stack/vmtool 相关单测、JarLocator 单测
- **Deployment:**
  - 发布包需同时包含：`java-sleuth-agent-*-jar-with-dependencies.jar`、`java-sleuth-agent-core-*-jar-with-dependencies.jar`、`java-sleuth-launcher-*-jar-with-dependencies.jar`
  - JarLocator marker 仍以 Manifest：`Sleuth-Agent-Bootstrap` / `Sleuth-Agent-Core` 为准

