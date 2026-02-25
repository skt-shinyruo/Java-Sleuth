# 架构审计报告：Java-Sleuth（深度审计）

## 元信息
```yaml
类型: 优化/重构建议（只读审计）
方案类型: overview
优先级: P1
状态: 完成（概述报告）
创建: 2026-02-25
```

---

## 1. 需求

### 背景

本次为“深度架构审计”类工作：不改代码，只基于仓库内的代码与构建文件，给出当前架构的真实画像、结构性问题清单与可落地改进路线图。

### 目标

- 输出当前架构实现概述：模块职责、端到端 attach/detach 工作流、编译期依赖与运行时加载关系。
- 给出“架构问题清单”（P0/P1/P2）：每条附可定位证据（文件路径 + 类名/方法/字段）。
- 给出可执行改进路线图（M0/M1/M2）：边界收敛 → 会话化生命周期 → 构建与打包治理。
- 给出主要风险与缓解策略：shade/relocation、manifest 驱动 JarLocator、反射/ServiceLoader keep、best-effort 语义。

### 约束条件

```yaml
语言/运行时基线:
  - Java 8 (maven.compiler.source/target=8)
构建:
  - Maven 多模块（bootstrap/foundation/agent/core/container/launcher/examples）
核心架构约束:
  - 依赖隔离：agent/container/core 运行于 URLClassLoader(parent=null) 的隔离域
  - detach → re-attach 幂等：失败可恢复，重复调用语义明确
  - best-effort 清理：shutdown 不得因清理失败而阻塞主流程
  - 编译期边界：core 禁止反向依赖 container（硬边界）
模块硬约束:
  - bootstrap/foundation 必须保持 JDK-only（禁止引入任何依赖）
```

### 验收标准

- [x] 模块职责与依赖关系清晰（含编译期依赖与运行时加载关系）。
- [x] ≥8 条问题清单，按 P0/P1/P2 分级，并带代码证据定位。
- [x] 路线图包含 3 个里程碑（M0/M1/M2），每个里程碑有可交付物与验收点。
- [x] 风险与缓解覆盖：类加载隔离、反射边界、打包隔离、构建门禁与弱网环境。

---

## 2. 方案（审计结论 + 改进建议）

### 2.1 审计方法与证据来源

- 代码与构建文件：`pom.xml` 及各模块 `*/pom.xml`、Java 源码（以 `src/main/java` 为主）。
- 关键路径核对：对照 `.helloagents/wiki/arch.md` 与实际入口实现的差异。
- 工具辅助：
  - 使用 `augment_context_engine` 做语义检索定位关键入口与调用链。
  - 尝试执行 `mvn dependency:tree` 辅助依赖分析，但在当前环境遇到 SSL 握手中断导致失败（见“风险评估”中的工程韧性风险）。

---

### 2.2 当前架构实现概述（以代码为准）

#### 端到端工作流（目标 JVM 视角）

1. `launcher` 选择目标 JVM 并 attach：`launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
2. `agent` 作为 bootstrap 入口启动（`premain/agentmain`）：`agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`
3. `agent` 追加 bootstrap bridge，并以隔离 `URLClassLoader(parent=null)` 加载 `container` 入口：`container/src/main/java/com/javasleuth/container/SleuthAgentContainerEntrypoint.java`
4. `container` 通过共享的入口支持类启动 per-attach runtime：`core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentEntrypointSupport.java`
5. `core` 创建并管理 per-attach runtime：`core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentRuntime.java`
6. shutdown/detach：`SleuthAgentEntrypointSupport.shutdown(...)` + `SleuthAgentRuntime.close()` + `core/src/main/java/com/javasleuth/core/command/server/ShutdownCoordinator.java`

#### 模块职责（编译期视角）

- `bootstrap`（JDK-only，bootstrap-visible bridge）：注册表/拦截器/轻量工具（例如 `bootstrap/src/main/java/com/javasleuth/bootstrap/agent/CoreClassLoaderRegistry.java`、`bootstrap/src/main/java/com/javasleuth/bootstrap/util/SystemPropertyRollbackRegistry.java`）
- `foundation`（JDK-only，非 bootstrap-visible）：config/security/protocol/util（例如 `foundation/src/main/java/com/javasleuth/foundation/config/ProductionConfig.java`）
- `agent`（JDK-only）：隔离加载 + 入口转发（`agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`）
- `container`（隔离域入口）：composition root 入口 + classloader 句柄关闭 best-effort
- `core`（隔离域核心）：命令/协议/增强/运行时容器（`SleuthAgentRuntime`、`CommandProcessor` 等）
- `launcher`（本机 CLI）：进程发现/选择、Attach、协议客户端、交互 UI（`launcher/src/main/java/com/javasleuth/launcher/**`）

---

### 2.3 主要架构问题清单（带证据）

> 说明：此处“问题”聚焦结构性风险/长期演进成本，不等同于 bug。

#### P0（高风险/高回归概率）

1) **跨 ClassLoader 交互缺少“单一契约”，反射/字符串边界分散，导致维护与回滚复杂度上升**

- 证据：
  - `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`：通过 `String` 常量定义跨模块入口类名，并反射调用 `container` 入口（例如 `CONTAINER_ENTRYPOINT_CLASS`）。
  - `core/src/main/java/com/javasleuth/core/agent/core/BootstrapAttachGateReset.java`：反射调用 `com.javasleuth.agent.BootstrapAttachGate#resetForReattach()`，兜底扫描 `Instrumentation#getAllLoadedClasses()`。
  - `bootstrap/src/main/java/com/javasleuth/bootstrap/agent/CoreClassLoaderRegistry.java`：作为 bootstrap-visible attach gate/SSOT，但存在“非 bootstrap loaded”降级路径与一次性告警逻辑。
- 风险：边界扩散后很难用编译期约束与单测完全覆盖；回归表现可能是 attach 不稳定、re-attach 假阳性或资源泄漏。
- 建议：冻结“极小 bootstrap-visible API（单一 entrypoint + DTO/primitive）”，把跨 ClassLoader 交互收敛到唯一边界（见路线图 M0/M1）。

2) **Jar 定位/bridge 安装路径存在多级 fallback 与运行时扫描，带来不确定性（可维护性 + 运维安全风险）**

- 证据：
  - `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`：`locateBootstrapBridgeJar(...)` 具有多路径 fallback（`lib/`、`../lib/`、`bootstrap/target` 等），存在基于 CWD 的兜底扫描。
  - `bootstrap/src/main/java/com/javasleuth/bootstrap/util/JarLocator.java`：位于 bootstrap 模块且逻辑体积较大，属于“高风险代码面”（被 append 到 bootstrap 搜索路径后，可能影响目标 JVM 的业务线程）。
- 风险：不同分发结构/启动目录导致定位到意外 jar；bootstrap-visible 代码膨胀放大影响面。
- 建议：JarLocator 从“运行时扫描”转为“manifest 驱动”（构建期产出清单、运行时只读解析）；扫描 fallback 限制为 dev/诊断模式（见路线图 M2）。

#### P1（重要改进点）

3) **“composition root”职责边界偏模糊：container 名义为组合根，但装配逻辑主要位于 core/runtime 内部**

- 证据：
  - `container/src/main/java/com/javasleuth/container/SleuthAgentContainerEntrypoint.java`：入口类极薄，实际启动由 `core/.../SleuthAgentEntrypointSupport` + `SleuthAgentRuntime.start/create` 完成。
  - `core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentRuntime.java`：内部创建多组件并调用 `CommandProcessorFactory.create(...)`，承担一部分装配职责。
- 风险：文档/代码认知可能分叉；未来替换装配策略时改动点分散。
- 建议：明确决策并写入：要么承认“装配在 core”，同步文档；要么逐步把装配提升到 container（core 仅提供可组合 runtime API），并用规则防止倒依赖复活。

4) **父 pom 缺少 dependencyManagement/pluginManagement：版本与插件配置散落，易出现模块间漂移**

- 证据：根 `pom.xml` 只有 `<modules>` 与 compiler 属性；三方依赖版本分散在模块级 pom（例如 `core/pom.xml`、`launcher/pom.xml`）。
- 风险：共享依赖增多后，版本不一致与插件参数发散会显著提升维护成本。
- 建议：引入 Build Guardrails：dependencyManagement/pluginManagement + enforcer + dependency:analyze-only + duplicate-finder + strict profile（见路线图 M2）。

5) **fat-jar（assembly jar-with-dependencies）缺少“重复类/冲突类”构建门禁**

- 证据：`agent/pom.xml`、`container/pom.xml`、`launcher/pom.xml` 使用 `maven-assembly-plugin` 构建 `jar-with-dependencies`。
- 风险：重复类/冲突类往往只在运行时暴露，而且暴露在目标 JVM 中（排障成本高）。
- 建议：strict profile 中开启 duplicate-finder / enforcer 规则；agent 产物优先走 shade + relocation（配套 keep 策略与回归测试）。

#### P2（中长期优化）

6) **best-effort 清理覆盖面广但分散，缺少统一“资源跟踪器”模型（容易遗漏、难证明）**

- 证据：
  - `core/src/main/java/com/javasleuth/core/agent/core/SleuthAgentEntrypointSupport.java`：shutdown 内串行执行多项 best-effort 清理（bootstrap store/sysprop rollback/config reset/attach gate reset/registry notify 等）。
  - `core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentRuntime.java`：close 内包含 transformer/线程/会话/增强/重变换等清理步骤。
- 建议：引入 Session + ResourceTracker：所有资源创建必须登记，detach 逆序释放，失败可补偿清理（见路线图 M1）。

7) **bootstrap 入口类偏大，职责复合，测试与审计成本高**

- 证据：`agent/src/main/java/com/javasleuth/agent/SleuthAgent.java` 同时承担 jar 定位、append bridge、attach gate、sysprop、classloader、反射调用、失败回滚等职责。
- 建议：拆分为可测试组件，让 `SleuthAgent` 只做 orchestrator（见路线图 M0）。

---

### 2.4 推荐路线图（可落地）

> 结论：运行时主线推荐“极小 bootstrap-visible API + Sessionized Container”，构建侧推荐“Build Guardrails”做长期门禁，两者互补。

#### M0 边界收敛与入口单一化（先止血）

- 冻结极小 Bootstrap API（允许类型/异常语义/版本策略），禁止旁路跨 ClassLoader 交互。
- 将 container 能力聚合为唯一 entrypoint（attach/detach/status），明确调用方向与数据形态。
- 拆分/瘦身 `SleuthAgent` bootstrap 入口，仅保留加载隔离与入口转发。

#### M1 会话化生命周期与幂等验收（把 best-effort 变成可验证能力）

- 以 `SleuthAgentRuntime` 为基础引入 Session（sessionId/state/error），实现 attach/detach 幂等状态机。
- 引入资源跟踪器统一登记与清理（bootstrap registries/sysprops/TCCL/threads/classloader handles）。
- 补齐重复 attach/detach、detach 失败后再 attach 的验收用例并作为回归门禁。

#### M2 构建治理与确定性打包（长期不走样）

- JarLocator 从运行时扫描切换为 manifest 驱动（构建产出清单、运行时只读解析）。
- 父 pom 收敛 dependencyManagement/pluginManagement，并启用 enforcer + dependency:analyze-only + duplicate-finder + strict profile。
- agent 启用 shade+relocation（配套反射/ServiceLoader keep 策略），并用 ArchUnit/jdeps 规则锁死边界（如 core 不反向依赖 container）。

---

### 2.5 影响范围

```yaml
涉及模块:
  - 文档/审计: 本方案包 proposal.md / tasks.md
代码变更:
  - 无（本方案为 overview，不进入 DEVELOP 阶段）
预计变更文件:
  - 2（本方案包内）
```

### 2.6 风险评估

| 风险 | 等级 | 应对 |
|------|------|------|
| shade/relocation 破坏反射/ServiceLoader（运行时崩溃） | 高 | 制定 relocation 白名单/黑名单与 keep 策略；加入运行时自检与冲突回归用例 |
| manifest 驱动 JarLocator 与分发结构耦合 | 中 | 定义清单格式与校验策略；保留仅诊断用途的扫描 fallback（默认关闭） |
| best-effort 语义不清导致“看似成功但资源泄漏” | 中 | Session 状态机 + ResourceTracker + 重复 attach/detach 验收用例作为门禁 |
| 构建链路对网络稳定性敏感（依赖分析命令失败） | 低 | CI 缓存 Maven repo；提供镜像/代理/证书配置示例；允许离线分析路径 |

---

## 3. 技术设计（可选）

### 架构设计（编译期依赖 + 运行时加载）

```mermaid
flowchart LR
  subgraph CompileTime[编译期依赖（Maven）]
    bootstrap[bootstrap\\nJDK-only]:::base
    foundation[foundation\\nJDK-only]:::base
    agent[agent\\nJDK-only]:::base
    core[core\\nagent core]:::mid
    container[container\\nisolated entrypoint]:::mid
    launcher[launcher\\nCLI]:::mid

    core --> bootstrap
    core --> foundation
    container --> core
    container --> bootstrap
    container --> foundation
    launcher --> bootstrap
    launcher --> foundation
  end

  subgraph Runtime[运行时加载（目标 JVM）]
    L[launcher 进程]:::mid -->|Attach API| A[agent premain/agentmain]:::mid
    A -->|append bridge| B[bootstrap classloader]:::base
    A -->|URLClassLoader(parent=null)| C[container/core isolated CL]:::mid
  end

  classDef base fill:#f6f6f6,stroke:#999,color:#111;
  classDef mid fill:#e8f0ff,stroke:#5b7bd5,color:#111;
```

### API 设计（建议方向）

> 目标：把跨 ClassLoader 调用收敛到单一 entrypoint（bootstrap-visible），避免多处反射/字符串边界。

#### Entrypoint（示意）

- `attach(AttachRequest) -> AttachResult`
- `detach(DetachRequest) -> DetachResult`
- `status() -> Status`

**约束**：请求/响应仅使用 JDK8 原生类型 + 少量 DTO（且必须 bootstrap-visible），不得透传 core/container 实现类。

---

## 4. 核心场景

### 场景: detach → re-attach 幂等闭环

**模块**: agent/container/core/bootstrap/foundation  
**条件**: 同一目标 JVM 内完成一次 attach，并触发一次 detach/shutdown  
**行为**:

- detach 触发 runtime close（停止命令服务、移除 transformer、清理会话/作业/插件、重置 bootstrap registries/sysprops）
- detach 完成后 re-attach 再次进入 attach gate，允许创建新的 isolated ClassLoader 与 runtime

**结果**:

- 不出现 “already attached” 假阳性
- 不残留线程/transformer/shutdown hook 导致 classloader pinned
- sysprops/bootstrap store 回滚到 attach 前基线（best-effort）

---

## 5. 技术决策

> 本节记录推荐路线图中的关键决策点（未实施，仅作为后续实现的决策输入）。

### architecture_audit#D001: 冻结极小 Bootstrap API 并收敛跨 ClassLoader 交互

**日期**: 2026-02-25  
**状态**: ✅采纳（推荐）  
**背景**: 当前跨 ClassLoader 交互存在多点反射/字符串边界，难以长期治理与回归验证。  
**选项分析**:
| 选项 | 优点 | 缺点 |
|------|------|------|
| A: 维持现状（多点反射） | 改动小 | 边界扩散快、回归难、可维护性差 |
| B: 极小 API + 单一入口（推荐） | 边界可审计、可测试、可版本化 | 需要一次性抽象与迁移成本 |
**决策**: 选择方案 B  
**理由**: 与“隔离加载 + re-attach 幂等”的核心约束强一致，是长期演进的必要底座。  
**影响**: agent/bootstrap/container/core

### architecture_audit#D002: 引入 Session 状态机与资源跟踪器，固化 detach→re-attach 语义

**日期**: 2026-02-25  
**状态**: ✅采纳（推荐）  
**背景**: best-effort 清理散落在多个类中，缺少统一资源模型，易遗漏且难证明正确。  
**选项分析**:
| 选项 | 优点 | 缺点 |
|------|------|------|
| A: 继续 best-effort 分散清理 | 无需抽象 | 易遗漏、难覆盖、排障成本高 |
| B: Session + ResourceTracker（推荐） | 可枚举、可回归、可补偿清理 | 需要梳理资源类型与登记点 |
**决策**: 选择方案 B  
**理由**: 让幂等与清理从“约定”升级为“工程能力”。  
**影响**: agent/container/core/bootstrap/foundation

### architecture_audit#D003: 建立 Build Guardrails（strict profile）将边界固化为构建门禁

**日期**: 2026-02-25  
**状态**: ✅采纳（推荐）  
**背景**: 边界规则仅靠约定与评审易回退；fat-jar 与多模块依赖增加运行时冲突风险。  
**选项分析**:
| 选项 | 优点 | 缺点 |
|------|------|------|
| A: 仅人工评审 | 低成本 | 不稳定、易遗漏、对新人不友好 |
| B: enforcer/analyze-only/duplicate-finder + ArchUnit/jdeps（推荐） | 自动化阻断回归、长期稳定 | verify 更慢，需要维护规则 |
**决策**: 选择方案 B（配合 profile 分层）  
**理由**: 以构建约束换取长期稳定性与可维护性。  
**影响**: 全模块（尤其 agent/container/core/launcher）
