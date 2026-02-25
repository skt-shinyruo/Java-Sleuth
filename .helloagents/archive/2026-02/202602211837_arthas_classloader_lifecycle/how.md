# Technical Design: Arthas 风格 ClassLoader 生命周期边界（attach/detach）

## Technical Solution

### Core Technologies
- Java Attach API / Instrumentation
- `URLClassLoader`（parent=`null`，隔离加载 core fat-jar）
- BootstrapClassLoader Search Path（`Instrumentation.appendToBootstrapClassLoaderSearch`）
- 反射调用 core 入口（避免 bootstrap agent 依赖 core 实现细节）
- 线程/Transformer/ShutdownHook 的显式生命周期治理（避免 ClassLoader 泄漏）

### Implementation Key Points

1. **Bootstrap 可见的 ClassLoader Registry/Bridge（JDK-only，状态 SSOT）**
   - 放置于 `bootstrap` 模块（保证 core 编译期可见；运行期可由 BootstrapClassLoader 加载）
   - 负责：
     - 登记本次 attach 创建的 core `URLClassLoader`
     - 提供幂等的 `onCoreShutdown(ClassLoader)`：若匹配当前登记的 loader，则 `close()` 并清引用
     - 提供 attach gate：若已登记且未释放，认为“已 attach”，拒绝重复 attach 或返回提示
   - 设计约束：
     - bridge 本身不持有 core 侧 `Class/Method` 等强引用（避免反向泄漏）
     - 仅存储 `ClassLoader` 对象引用（以及必要的时间戳/状态位）

2. **Bootstrap agent（SleuthAgent）重构 attach gate**
   - 不再使用不可重置的 `static ATTACHED` 作为终身开关
   - attach 流程：
     1) 先 best-effort `appendToBootstrapClassLoaderSearch(selfJar)`（确保 bridge 类可被 BootstrapClassLoader 加载）
     2) 创建 core `URLClassLoader`（隔离）
     3) 通过 bridge CAS 登记该 loader（失败则说明已有 attach 在运行，直接返回）
     4) 设置当前线程 TCCL=core loader，反射调用 `com.javasleuth.agent.core.SleuthAgentCore.agentmain(agentArgs, inst)`
   - 异常回滚：
     - 若 core 启动失败，必须通过 bridge 释放登记的 loader（close + 清引用），避免“半 attach 卡死”导致永远无法再次 attach

3. **core shutdown 触发 bootstrap 释放 ClassLoader（Arthas 风格 detach）**
   - 在 `SleuthAgentCore.shutdown()` 中（runtime close 完成后）：
     - 调用 bridge 的 `onCoreShutdown(SleuthAgentCore.class.getClassLoader())`
   - 这样可以：
     - 解除 bootstrap 侧 attach gate
     - `URLClassLoader.close()` 释放 JAR 句柄（尤其 Windows）
     - 让 core/foundation 的 static/singleton 随 ClassLoader 一并消亡（减少 `shutdownInstance()` 依赖）

4. **关闭编排收敛：让 `ShutdownCoordinator` 回归“实例资源释放”**
   - 推荐调整：
     - `ShutdownCoordinator` 只处理注入实例的 shutdown（auth/authz/request security/danger confirm/audit/memory optimizer 等）
     - 将 `AuthenticationManager.shutdownInstance()` / `DangerousCommandConfirmationManager.shutdownInstance()` 这类“全局单例 reset”从 `ShutdownCoordinator` 移除（或降级为非核心路径），由 ClassLoader 释放承担主要 reset 语义
   - bootstrap 侧的全局静态注册表（watch/trace/monitor/tt/stack 等）仍由 `AgentGlobalState.resetInterceptorsBestEffort()` 统一清理（这些属于 bootstrap 可见、不会随 core ClassLoader 卸载）

## Architecture Design

```mermaid
flowchart TD
  A[Attach API 调用 agent jar] --> B[SleuthAgent (bootstrap agent)]
  B --> C[append self jar to BootstrapClassLoaderSearch]
  C --> D[BootstrapClassLoader: Bridge/Registry (SSOT)]
  B --> E[Create core URLClassLoader (parent=null)]
  E --> F[Load SleuthAgentCore in isolated CL]
  F --> G[SleuthAgentRuntime (per attach)]
  G --> H[Command Server + Transformer + Threads]

  H -->|shutdown/stop| G
  G -->|close done| F
  F -->|notify| D
  D -->|close + clear ref| E
```

## Architecture Decision ADR

### ADR-001: 以隔离 ClassLoader 作为 attach 生命周期边界（并由 bootstrap bridge 负责释放）
**Context:** 当前通过 `ShutdownCoordinator` best-effort 清理多个全局单例/静态状态来支持 detach→re-attach，关闭路径复杂且易遗漏；并且 core ClassLoader 目前无法被显式关闭，存在 JAR 锁与 ClassLoader 泄漏风险。  
**Decision:** 引入 bootstrap 可见的 ClassLoader registry/bridge 作为 attach gate 与释放收口点；在 core shutdown 完成后回调 bridge 关闭并释放 core `URLClassLoader`，使 core/foundation static 状态随 ClassLoader 自然消亡。  
**Rationale:**  
- Arthas 证明了“隔离 ClassLoader + detach 时 close classloader”是一条工程上可行的稳定路径  
- 将“可变状态”从“散落 static”迁移为“可销毁的生命周期容器”，降低推理复杂度与测试隔离成本  
- 不引入 DI 框架，保持 agent 侧依赖最小化与隔离策略  
**Alternatives:**  
- 继续依赖 `shutdownInstance()` + best-effort 清理 → 拒绝原因：关闭编排持续膨胀且不可验证覆盖完整  
- 彻底去单例（全部改为 per-attach 实例注入） → 拒绝原因：改动面更大、兼容性风险更高（可作为后续方案 2）  
**Impact:**  
- `agent`/`core` 需要增加一条“shutdown 回调 → bootstrap 释放 ClassLoader”的闭环  
- 需要补充单测验证 bridge 的幂等与并发行为，并在必要时降级处理 append 失败场景

## Security and Performance
- **Security:** 不新增对外接口；detach/release 能力仅由内部 shutdown 路径触发；避免在日志中输出敏感 agentArgs（若包含 secret/path）
- **Performance:** attach/detach 阶段增加少量 CAS/反射/close 开销；运行期无额外 hot-path 开销

## Testing and Deployment
- **Testing:**
  - 单测覆盖 bridge 的 register/release 幂等与并发边界（重复 attach、错误 loader 释放、异常回滚）
  - 集成/冒烟：同 JVM 流程 attach→shutdown→re-attach（至少保证不被 attach gate 永久拒绝；并验证关键线程池/Transformer 已移除）
- **Deployment:** 无需额外部署步骤；产物仍为 bootstrap agent + core fat-jar（通过 JarLocator 定位）

