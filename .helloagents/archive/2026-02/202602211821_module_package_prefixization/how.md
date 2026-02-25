# Technical Design: bootstrap/foundation/core 模块包根前缀化（消除 split package）

## Technical Solution

### Core Technologies
- Java 8
- Maven multi-module
- ASM（运行时增强）
- Attach API（agentmain）

### Implementation Key Points
1. **模块包根迁移映射（建议一次性落地，分阶段编译验证）：**
   - `bootstrap`：
     - `com.javasleuth.util.*` → `com.javasleuth.bootstrap.util.*`
     - `com.javasleuth.monitor.*` → `com.javasleuth.bootstrap.monitor.*`
     - `com.javasleuth.data.*` → `com.javasleuth.bootstrap.data.*`
   - `foundation`：
     - `com.javasleuth.util.*` → `com.javasleuth.foundation.util.*`
     - `com.javasleuth.config.*` → `com.javasleuth.foundation.config.*`
     - `com.javasleuth.security.*` → `com.javasleuth.foundation.security.*`
     - `com.javasleuth.compiler.*` → `com.javasleuth.foundation.compiler.*`（如存在）
   - `core`：
     - `com.javasleuth.command.*` → `com.javasleuth.core.command.*`
     - `com.javasleuth.enhancement.*` → `com.javasleuth.core.enhancement.*`
     - `com.javasleuth.vmtool.*` → `com.javasleuth.core.vmtool.*`
     - `com.javasleuth.monitoring.*` → `com.javasleuth.core.monitoring.*`
     - `com.javasleuth.util.*`（core 专用）→ `com.javasleuth.core.util.*`
     - `com.javasleuth.agent.core.*` / `com.javasleuth.agent.runtime.*`（core 内部）→ `com.javasleuth.core.agent.*`（保持层级一致）
2. **入口与字符串引用同步更新：**
   - `agent` 侧 `CORE_ENTRYPOINT_CLASS` 字符串需更新为新的 core 入口类全限定名。
   - 所有 ASM 生成代码使用的 internalName（`com/javasleuth/...`）需同步迁移到新包根。
3. **类加载域边界保持不变：**
   - 仍由 agent 将 bridge/spy 类型 append 到 bootstrap search。
   - core 仍通过 isolated `URLClassLoader(..., null)` 加载运行，父加载器为 bootstrap。
4. **防回归约束：**
   - 在实现阶段增加检查：主源码中不允许出现跨模块重复包根（尤其是 `com.javasleuth.util`）。
   - bootstrap 可见包根白名单（建议仅允许 `com.javasleuth.bootstrap.*`）。

## Architecture Design
```mermaid
flowchart TD
    L[launcher] -->|Attach| A[agent (bootstrap entry)]
    A -->|append bootstrap bridge| B[BootstrapClassLoaderSearch]
    A -->|load isolated core| C[isolated URLClassLoader parent=null]
    C -->|call| BC[bootstrap bridge classes]
    C -->|run| CORE[core implementation]
```

## Architecture Decision ADR

### ADR-001: 采用模块前缀包根（bootstrap/foundation/core）
**Context:** 现有 `com.javasleuth.util` split package 使 bootstrap 可见边界不显式，且 core 直接复用 bootstrap 实现细节，增加 bootstrap 域暴露面与兼容风险。  
**Decision:** 将 `bootstrap/foundation/core` 的代码包根迁移到模块前缀命名空间（`com.javasleuth.bootstrap.*` / `com.javasleuth.foundation.*` / `com.javasleuth.core.*`），并同步更新所有引用（含 ASM internalName、入口字符串、测试）。  
**Rationale:** 命名即边界，可审计、可自动化校验；彻底消除 split package 与类解析不确定性；降低误用 bootstrap 细节的概率。  
**Alternatives:**  
- 最小改动（仅迁移 `bootstrap util`）：拒绝原因：边界显式性不足，未来仍可能在其它包出现同类问题。  
- 构建期 relocate（shade）：拒绝原因：源码边界仍不直观，构建复杂度与排障成本更高。  
**Impact:** 大范围重命名与引用更新；需要分阶段迁移、全量测试、以及对 ASM 生成点进行专项回归。

## API Design
N/A（对外命令/协议不变，变更为内部包名）

## Data Model
N/A

## Security and Performance
- **Security:** 保持 `bootstrap` JDK-only 约束；避免将 `foundation/core` 任意实现类暴露到 bootstrap 域；迁移过程中重点排查反射与字符串拼接导致的越界引用。
- **Performance:** 包名迁移不引入运行时额外开销；ASM 生成逻辑仅更新目标类名，不改变运行时热路径算法。

## Testing and Deployment
- **Testing:** 分阶段运行 `mvn test`（至少覆盖 `bootstrap/foundation/core/launcher`）；增加针对 ASM 生成点的 smoke test（验证 className/internalName 未漏改）。
- **Deployment:** 打包方式保持不变（assembly fat-jar）；发布前做一次 attach 端到端冒烟（启动→attach→执行基础命令）。
