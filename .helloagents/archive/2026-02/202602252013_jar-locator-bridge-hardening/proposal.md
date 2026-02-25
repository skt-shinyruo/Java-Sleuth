# 变更提案: jar-locator-bridge-hardening

## 元信息
```yaml
类型: 优化
方案类型: implementation
优先级: P1
状态: 已完成
创建: 2026-02-25
```

---

## 1. 需求

### 背景
Jar 定位 / bootstrap bridge 安装路径当前存在多级 fallback 与运行时扫描，定位结果依赖运行时环境（CWD、classpath、文件修改时间等），带来：
- **不确定性**：同版本在不同机器/不同启动方式下可能选中不同 jar，或找不到 jar。
- **运维风险**：缺少“到底走了哪一级 fallback、最终命中了什么”的可观测输出，排障成本高。
- **bootstrap 风险面扩大**：`bootstrap` 模块的 `JarLocator` 属于 bootstrap-visible 且包含扫描逻辑，问题更敏感且更难隔离。

证据文件：
- `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`：bridge jar 多路径 fallback（含 CWD 相对扫描、按 `lastModified` 选“最新”）。
- `bootstrap/src/main/java/com/javasleuth/bootstrap/util/JarLocator.java`：agent/core/container jar 定位逻辑体积较大，包含 classpath/CWD 扫描。

### 目标
兼容优先（默认行为不破坏），在此基础上实现以下收敛：
1) **可观测性**：在关键 fallback（尤其 CWD 相对扫描）触发时输出明确提示；提供 debug 开关输出更完整的解析链路。
2) **更确定的候选选择**：当同目录存在多个候选 jar 时，优先按“文件名版本号”进行确定性选择；无法解析版本时才回退到 `lastModified`。
3) **可控的扫描边界**：新增可选开关用于禁用 CWD 相对扫描（默认保持开启以兼容既有使用方式）。
4) **测试与文档同步**：补齐单测覆盖关键选择逻辑，并更新使用文档（bridge/container override、诊断开关）。

### 约束条件
```yaml
时间约束: 本次以可交付的兼容性增强为主（不做结构性重构到 DBP）。
性能约束: 默认路径不增加显著扫描开销；debug 仅在开关开启时输出/计算更多细节。
兼容性约束: 默认继续支持现有 fallback；新增开关默认不改变行为。
技术约束:
  - agent/bootstrap 保持 JDK-only（Java 8 基线），不引入三方依赖。
  - bootstrap-visible 面尽量不扩大（新增代码以小而可测的 helper 为主）。
```

### 验收标准
- [x] 默认行为不破坏：不设置新开关时，jar 定位仍可在现有典型路径工作。
- [x] 多候选时优先按版本确定性选择（可通过单测验证）。
- [x] CWD 相对扫描可通过开关禁用（可通过单测验证）。
- [x] 关键 fallback 触发时有明确提示；debug 开关可输出解析摘要（bridge CWD 命中有一次性提示，定位 debug 由开关控制）。
- [x] `mvn test` 通过。
- [x] 文档更新：`docs/usage/getting-started.md` 覆盖 bridge/container override 与诊断建议。

---

## 2. 方案

### 技术方案
本次选择“渐进式收敛（Deterministic-first, Scan-last）”：

1) **统一开关（兼容默认）**
- 新增 System property：`sleuth.locator.allowCwdScan`（默认 `true`）
  - `JarLocator`：控制是否执行 `agent/target`、`container/target`、`lib` 等 CWD 相对目录扫描。
  - `SleuthAgent`：控制是否执行 `lib`、`../lib`、`bootstrap/target` 等 CWD 相对目录扫描（bridge jar 最后兜底）。

2) **更确定的候选选择（不破坏 fallback 层级）**
- `SleuthAgent#locateNewestJarByPrefix`：当存在多个 `java-sleuth-bootstrap-bridge-*` 候选时：
  - 优先解析文件名中的版本片段并做“版本自然序”比较（数字按数值比较），选最大版本；
  - 版本不可解析时回退到 `lastModified`。
- `JarLocator#locateNewest*JarBySuffix`：同理，多个 `*-jar-with-dependencies.jar` 候选时优先按版本选择。

3) **可观测性增强（默认低噪音）**
- 在进入 CWD 相对扫描并命中 jar 时输出一次性提示（降低“默默兜底”的排障成本）。
- 提供 debug 开关（`sleuth.locator.debug=true` 或复用 `sleuth.agent.bootstrap.debug=true`）时输出更详细的解析信息（命中来源、候选列表摘要等）。

4) **测试与文档**
- 为版本选择与 CWD 开关补齐单测（集中在 `JarLocatorTest`）。
- 更新 `docs/usage/getting-started.md`：补齐 bridge/container override 与推荐实践（生产显式路径、避免依赖 CWD 扫描）。

### 影响范围
```yaml
涉及模块:
  - agent: SleuthAgent 的 bridge jar 定位策略与日志
  - bootstrap: JarLocator 的候选选择与扫描边界
  - launcher: 仅可能补充诊断输出（尽量不改 attach 语义）
  - docs: 快速上手文档补齐
  - test: JarLocatorTest 增量覆盖
预计变更文件: 5-9
```

### 风险评估
| 风险 | 等级 | 应对 |
|------|------|------|
| 版本解析/排序逻辑存在边界错误导致“选错”候选 | 中 | 保留 lastModified 兜底；增加单测覆盖；debug 输出便于定位 |
| 新增日志在部分场景造成噪音 | 低 | 仅在 CWD 兜底命中时提示；更详细信息放在 debug 开关下 |
| 禁用 CWD 扫描后旧用法不可用 | 中 | 默认不禁用；文档明确开关用途与迁移路径 |
| bootstrap-visible 面扩大带来维护成本 | 低 | helper 尽量小且可测；不引入新依赖；代码复用避免重复实现 |

---

## 3. 技术设计（可选）

> 涉及架构变更、API设计、数据模型变更时填写

### 架构设计
```mermaid
flowchart TD
    subgraph LauncherSide[控制端（Launcher）]
      L1[SleuthLauncher] --> L2[JarLocator.locateAgentJar/locateAgentContainerJar]
    end
    subgraph TargetJvm[目标 JVM 内]
      A1[SleuthAgent] --> A2[locateBootstrapBridgeJar]
      A2 --> A3[appendToBootstrapClassLoaderSearch]
      A3 --> A4[JarLocator (bootstrap-visible)]
      A4 --> A5[locateAgentContainerJar/CoreJar]
      A5 --> A6[隔离 URLClassLoader 加载 container/core]
    end
```

### API设计
#### {METHOD} {路径}
- **请求**: {结构}
- **响应**: {结构}

### 数据模型
| 字段 | 类型 | 说明 |
|------|------|------|
| {字段} | {类型} | {说明} |

---

## 4. 核心场景

> 执行完成后同步到对应模块文档

### 场景: 多版本 jar 共存导致误选
**模块**: agent/bootstrap
**条件**: 同目录存在多个候选 jar（例如 `java-sleuth-bootstrap-bridge-*` 或多个 `*-jar-with-dependencies.jar`）
**行为**: 现状按 `lastModified` 选“最新”，容易受复制时间影响
**结果**: 同版本在不同机器上可能选中不同 jar，造成不可复现问题；本次改造优先按“文件名版本”确定性选择

### 场景: 依赖 CWD 相对扫描才能找到 jar
**模块**: agent/bootstrap
**条件**: 未显式配置 override，且 jar 仅能通过 `lib/`、`../lib`、`agent/target` 等相对目录命中
**行为**: 进入最后兜底扫描
**结果**: 行为依赖运行目录；本次改造提供一次性提示 + 可选开关禁用该兜底

---

## 5. 技术决策

> 本方案涉及的技术决策，归档后成为决策的唯一完整记录

### jar-locator-bridge-hardening#D001: 兼容优先的确定性增强（Deterministic-first, Scan-last）
**日期**: 2026-02-25
**状态**: ✅采纳
**背景**: 用户选择“兼容优先”，目标是在不破坏默认行为前提下增强确定性、可观测性与可控性。
**选项分析**:
| 选项 | 优点 | 缺点 |
|------|------|------|
| A: 渐进式收敛（本次采用） | 兼容性强；可快速降低不确定性与排障成本；可分阶段推进更严格策略 | 仍保留扫描兜底（默认开启） |
| B: 结构性收敛（DBP） | bootstrap 面最小、确定性最强 | 需要交付物/发布方式调整，超出本次范围 |
**决策**: 选择方案 A
**理由**: 满足兼容优先前提下的风险治理诉求，且改动面可控、可测试。
**影响**: 主要涉及 agent/bootstrap（定位与选择逻辑）、docs/test（同步更新）。 

---

## 6. 实施结果（摘要）

- 新增开关：`sleuth.locator.allowCwdScan`（默认 true），可用于禁用 CWD 相对目录兜底扫描（兼容优先，不改变默认）。
- 增强确定性：多候选 jar 优先按“文件名版本号”选择（无法解析版本再回退 `lastModified`）。
- 可观测性：bridge jar 通过 CWD 相对扫描命中时输出一次性提示（建议显式配置 `sleuth.agent.bootstrap.bridge.jar`）；定位 debug 可通过 `sleuth.locator.debug`（或 `sleuth.agent.bootstrap.debug`）开启。
- 回归验证：`mvn test` 通过。
