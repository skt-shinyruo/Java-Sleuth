# 变更提案: jar-locator-bridge-risk

## 元信息
```yaml
类型: 优化
方案类型: overview
优先级: P1
状态: 完成
创建: 2026-02-25
```

---

## 1. 需求

### 背景
- Jar 定位 / bootstrap bridge 安装路径目前依赖**多级 fallback + 运行时扫描**，输入来源包含 System properties / 环境变量 / agentArgs / `java.class.path` / 当前工作目录（CWD）等，导致定位结果与启动方式强耦合，带来不确定性与排障难度。
- `bootstrap` 模块的 `JarLocator` 通过 bridge jar 被 append 后处于 **bootstrap-visible**（可被 `BootstrapClassLoader` 加载），文件体积较大（约 621 LOC），包含 classpath/文件系统扫描逻辑，扩大 bootstrap 风险面：任何缺陷都更难隔离与回滚。

**证据（关键代码点）**：
- `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`
  - `locateBootstrapBridgeJar(...)`（约 L193 起）：bridge jar override → 同目录/开发目录/相对目录扫描与 fallback。
  - `locateNewestJarByPrefix(...)`（约 L330 起）：按 `lastModified` 选择“最新”候选。
- `bootstrap/src/main/java/com/javasleuth/bootstrap/util/JarLocator.java`
  - `locateAgentJar/locateAgentCoreJar/locateAgentContainerJar(...)`（约 L66/L117/L149 起）：override → `java.class.path` → `CodeSource` → CWD 相对目录扫描。
  - `locateJarOnClasspath(...)`（约 L211 起）：遍历 `java.class.path`。
- `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
  - `start(...)`（约 L76 起）：启动前调用 `JarLocator`，定位 agent/container jar，直接影响 attach 可用性。
- 文档现状：
  - `docs/usage/getting-started.md` 提示“不在项目根目录运行时需显式指定 `-Dsleuth.agent.jar` 等”，但 bridge jar override（`sleuth.agent.bootstrap.bridge.jar`）与 container override 文档缺失。

### 目标
- 输出一份“现状定位链路”梳理：优先级顺序、扫描范围、关键校验点、失败与降级行为。
- 给出风险清单并分级：确定性 / 运维可观测性 / 安全面 / 性能面 / 可维护性。
- 给出两套可行收敛方案并对比：**方案A（渐进式收敛，兼容优先）** 与 **方案B（结构性收敛，确定性优先）**，并给出推荐路线。
- 给出运维侧可立即采用的“无代码改造”建议（参数规范、部署布局、诊断方式）。

### 约束条件
```yaml
时间约束: 本次仅做现状分析与建议输出（不进入代码改造实施）。
性能约束: 不引入任何额外运行时代价（仅输出文档）。
兼容性约束: 建议方案需包含“渐进收敛”路径，避免一次性破坏现有行为。
业务约束: 产物以文档/方案包形式交付，作为后续改造输入。
```

### 验收标准
- [x] 已梳理 `SleuthAgent` 的 bridge jar 定位链路（含 override 与多级 fallback）。
- [x] 已梳理 `JarLocator` 的 agent/core/container 定位链路（含 classpath/CodeSource/CWD 扫描）。
- [x] 已输出风险清单与分级，并给出两套收敛方案对比与推荐路线。
- [x] 已补充“运维侧立即可用”的实践建议（参数、目录结构、诊断与日志建议）。

---

## 2. 方案

### 2.1 现状梳理（定位链路）

#### 2.1.1 Bootstrap bridge jar（`SleuthAgent#locateBootstrapBridgeJar`）

bridge jar 定位优先级（从高到低）：
- **显式 override（确定性最高）**
  - System property：`-Dsleuth.agent.bootstrap.bridge.jar=<path>`
  - 环境变量：`SLEUTH_AGENT_BOOTSTRAP_BRIDGE_JAR=<path>`
  - agentArgs：支持 `bootstrapBridgeJar=<path>` / `bridgeJar=<path>` / `agent.bootstrap.bridge.jar=<path>`
- **agent 自身 CodeSource 同目录扫描（半确定）**
  - 扫描 `agentJar` 同目录下前缀 `java-sleuth-bootstrap-bridge-` 的 jar（按 `lastModified` 选“最新”）
  - 回退扫描前缀 `java-sleuth-bootstrap-`（兼容旧命名）
- **开发目录扫描（仅适合源码 checkout）**
  - 从 `.../agent/target` 推断项目根目录，扫描 `<root>/bootstrap/target` 与 `<root>/lib`
- **最后兜底：CWD 相对目录扫描（不确定性最高）**
  - `lib`、`../lib`、`bootstrap/target`

关键校验与失败行为：
- append 之前会拒绝“非最小 bridge jar”（包含 `com/javasleuth/agent/` 条目则拒绝）。
- append 失败会打印 `System.err`，随后通过 `isBootstrapBridgeAvailableBestEffort()` 检测关键类是否由 `BootstrapClassLoader` 可见；不可用时 **fail-fast** 退出启动，避免后续增强注入导致 `NoClassDefFoundError/LinkageError`。

#### 2.1.2 Container/Core jar（`JarLocator`）

`JarLocator` 提供三类产物定位：
- `locateAgentJar(Class<?> anchor)`：bootstrap agent jar（manifest 有 `Agent-Class` / `Premain-Class` 即视为 agent jar）
- `locateAgentCoreJar(Class<?> anchor)`：core fat-jar（manifest marker `Sleuth-Agent-Core=true`）
- `locateAgentContainerJar(Class<?> anchor)`：container fat-jar（manifest marker `Sleuth-Agent-Container=true`）

共同优先级骨架：
- **显式 override**
  - `sleuth.agent.jar` / `SLEUTH_AGENT_JAR`
  - `sleuth.agent.core.jar` / `SLEUTH_AGENT_CORE_JAR`
  - `sleuth.agent.container.jar` / `SLEUTH_AGENT_CONTAINER_JAR`
  - 注：`bootstrap/util/AgentArgsApplier` 会把 agentArgs 中的 `coreJar` / `containerJar` 写入对应 sysprop，间接影响 override。
- **classpath 扫描**：遍历 `java.class.path`，按后缀 `-jar-with-dependencies.jar` 过滤，再按 manifest 识别 jar 类型。
- **CodeSource 扫描**：从 anchor 的 `ProtectionDomain/CodeSource` 推断 jar 或目录；若为目录，会向上最多 6 层寻找匹配 jar。
- **CWD 相对目录扫描（兜底）**
  - agent：`agent/target`、`core/target`、`target`、`lib`、`../lib`
  - container：`container/target`、`target`、`lib`、`../lib`

#### 2.1.3 调用链（谁在什么时候触发定位）
- launcher 侧：`SleuthLauncher#start` 启动时调用 `JarLocator.locateAgentJar/locateAgentContainerJar`，定位失败会直接阻断 attach。
- agent 侧：`SleuthAgent` 在确认 bootstrap bridge 可见之后，通过反射调用 `JarLocator.locateAgentContainerJar/locateAgentCoreJar` 找到并隔离加载 container/core。

### 2.2 风险评估（不确定性与运维风险来源）

1) **确定性风险（高）**
- 多候选时按 `lastModified` 选“最新”，容易受复制/解压/镜像构建时间影响，导致同版本在不同机器上选中不同产物。
- CWD 相对目录兜底使定位结果依赖启动目录（`user.dir`），与容器/发布系统工作目录耦合。

2) **运维可观测性风险（中-高）**
- 关键定位决策缺少结构化输出：难以回答“走了哪一级 fallback、为何失败、最终命中来源是什么（override/classpath/CodeSource/CWD）”。
- 文档缺口：已提示 `-Dsleuth.agent.jar` 等，但未覆盖 bridge/container override 与 agentArgs 的相关键。

3) **安全面风险（中）**
- bootstrap-visible 工具类较大，包含文件系统与 jar manifest 读取逻辑；bootstrap 面越大，越难审计、越难隔离。
- override 与兜底路径受 sysprop/classpath/CWD 影响，存在误命中或被投毒的理论风险（尽管 core/container 通过 marker 有一定约束）。

4) **性能面风险（低-中）**
- classpath 遍历与目录扫描（`listFiles` + `JarFile#getManifest`）在 classpath/目录规模较大时可能带来启动/attach 抖动。

### 2.3 收敛方案对比（面向后续落地）

| 方案 | 核心思路 | 优点 | 缺点/代价 | 适用场景 |
|------|----------|------|-----------|----------|
| **A（推荐，短期）**：显式锚点 + 渐进式收敛（Deterministic-first, Scan-last） | 新增稳定显式配置入口与结构化可观测性；保留扫描作为最后兜底，但把触发原因/命中来源完整暴露，并提供开关逐步限制高风险扫描面 | 兼容性强；可快速降低运维不确定性；利于渐进迁移 | 仍保留部分扫描与 bootstrap 面；需要后续阶段性推动默认值收敛 | 线上环境希望稳定但短期难改发布流水线；混合部署（裸机/容器） |
| **B（长期方向）**：DBP（Deterministic Boot Payload） | 结构性改造：把 Jar 定位与安装逻辑移出 bootstrap-visible；bootstrap 仅保留最小桥接类；bridge/boot payload 通过显式路径或内嵌资源确定性供给 | 确定性最高；bootstrap 风险面显著缩小；启动抖动下降 | 交付物/发布方式需调整；需要迁移窗口；“零配置自动发现”体验会下降 | 安全/确定性优先的生产环境；大规模集群 |

### 2.4 推荐路线（建议）
- **短期（优先落地 A）**：先把“不确定性”变成“可观测、可控、可逐步关闭”的兜底机制（把扫描变成显式逃生舱）。
- **中长期**：评估并推进 B，把 bootstrap-visible 面最小化，把定位逻辑统一沉到 agent/launcher 侧（非 bootstrap）。

### 2.5 运维侧立即建议（无需改代码）
- 生产环境尽量**显式指定** jar 路径（至少 `-Dsleuth.agent.jar`；如遇 container/bridge 定位不稳，也建议显式提供对应 override）。
- 避免从随机 CWD 启动 launcher；推荐固定工作目录或显式传参，避免触发相对目录扫描。
- 将 Java-Sleuth 产物以“单一目录”交付（类似 `JAVA_SLEUTH_HOME`），减少多版本并存导致的误选。

### 影响范围
```yaml
涉及模块:
  - agent: SleuthAgent 的 bridge jar 定位与日志/校验（若落地方案A/B）
  - bootstrap: JarLocator 的定位策略、可观测性与扫描边界（若落地方案A/B）
  - launcher: 启动前 jar 解析的行为与报错提示（若落地方案A/B）
  - docs: 运维与快速上手文档补齐（bridge/container override、最佳实践）
预计变更文件: 4-10（预估，取决于选择方案A还是方案B）
```

### 风险评估
| 风险 | 等级 | 应对 |
|------|------|------|
| 继续依赖 CWD/lastModified 扫描导致线上不确定性（选错/选不到） | 高 | 生产显式 override；方案A 增加可观测与逐步关闭扫描；方案B 结构性消除扫描 |
| 误命中其他 agent jar（classpath/lib 中存在同后缀/带 Agent-Class 的 jar） | 高 | 增强识别（marker+名称/签名/哈希）；strict 模式；限制扫描目录范围 |
| bootstrap-visible 工具类过大导致审计/回归成本升高 | 中 | 收敛 bootstrap 面（B）；A 中先把日志/诊断打通并缩小兜底范围 |
| 引入 strict/禁用扫描后对旧部署不兼容 | 中 | 分阶段迁移：先告警后默认收敛；保留逃生舱开关；提供迁移指引 |
| 日志增加导致噪音或泄露路径信息 | 低 | 默认 debug/诊断开关输出；生产仅输出必要字段并脱敏（如需要） |
| cache/内嵌落盘方案带来只读文件系统适配问题（方案B） | 中 | 提供纯显式路径模式；可配置 cacheDir；对只读环境给出明确报错与替代方案 |

---

## 3. 技术设计（可选）

### 架构设计（定位链路抽象）
```mermaid
flowchart TD
    subgraph LauncherSide[控制端（Launcher）]
      L1[SleuthLauncher] --> L2[JarLocator.locate*]
    end
    subgraph TargetJvm[目标 JVM 内]
      A1[SleuthAgent(premain/agentmain)] --> A2[locateBootstrapBridgeJar]
      A2 --> A3[appendToBootstrapClassLoaderSearch(bridgeJar)]
      A3 --> A4{bootstrap bridge 可用?}
      A4 -->|否| A5[fail-fast 退出]
      A4 -->|是| A6[反射调用 JarLocator.locateAgentContainerJar/CoreJar]
      A6 --> A7[隔离 URLClassLoader(parent=null) 加载 container/core]
    end
```

---

## 4. 核心场景

### 场景: 运行目录不固定导致 jar 定位不稳定
**模块**: launcher/bootstrap
**条件**: 从非项目根目录或容器默认工作目录启动 launcher，且未显式指定 override
**行为**: `JarLocator` 进入 classpath/CodeSource/CWD 扫描兜底，命中结果依赖环境差异
**结果**: 可能“找不到 jar”或命中非预期版本，导致 attach 失败或行为差异；排障成本升高

### 场景: 多版本 jar 共存导致 lastModified 误选
**模块**: agent/bootstrap
**条件**: 同目录存在多个 `java-sleuth-bootstrap-bridge-*` 或多个 `*jar-with-dependencies.jar`
**行为**: 按 `lastModified` 选“最新”候选
**结果**: 复制/解压/镜像构建时间改变后，可能选中不同版本；造成不可复现问题

### 场景: bridge jar append 失败或不可见
**模块**: agent/bootstrap
**条件**: bridge jar 路径错误/不可读/append 失败
**行为**: `SleuthAgent` 打印 `System.err`，随后检测 bootstrap bridge 可见性并 fail-fast
**结果**: attach 请求失败（保护目标 JVM，避免后续增强注入导致运行时崩溃）

---

## 5. 技术决策

### jar-locator-bridge-risk#D001: Jar 定位策略收敛路线（A 渐进式收敛优先）
**日期**: 2026-02-25
**状态**: ✅采纳（作为推荐路线）
**背景**: 当前多级 fallback + 运行时扫描导致不确定性与运维风险；需要在兼容现状与提升确定性之间取得可迁移的平衡。

**选项分析**:
| 选项 | 优点 | 缺点 |
|------|------|------|
| A: 显式锚点 + 渐进式收敛（Deterministic-first, Scan-last） | 兼容性强；快速提升可观测性与可控性；可分阶段逐步关闭高风险扫描面 | 仍保留部分扫描与 bootstrap 面，需后续持续治理 |
| B: DBP（Deterministic Boot Payload）结构性收敛 | 确定性最高；bootstrap 面最小；更利于安全审计与稳定性 | 交付物/发布方式需调整；需要迁移窗口；短期成本更高 |

**决策**: 短期推荐采用方案 A；方案 B 作为中长期方向评估推进。

**理由**: 本问题以“运维不确定性与风险治理”为主，短期优先把现状变得可观测、可控、可逐步收敛；在此基础上再推进结构性最小化。

**影响**: 若落地改造，主要影响 agent/bootstrap/launcher/docs 四个模块的定位策略、日志与文档。
