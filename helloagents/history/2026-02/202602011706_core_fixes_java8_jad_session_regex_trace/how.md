# Technical Design: Java 8 兼容性与诊断稳定性修复（jad/session/regex/trace/watch/tt）

## Technical Solution

### Core Technologies
- Java 8 运行时兼容（语言级别与 JDK API 基线）
- Maven 构建（maven-compiler-plugin + 兼容性校验插件）
- CFR（反编译）
- ASM（字节码插桩）
- 现有安全/校验体系（InputValidator / Authorization / Audit）
- （待确认）re2j 线性时间正则引擎（用于 `-E` 场景抗 ReDoS）

### Implementation Key Points

#### 1) Java 8 兼容策略“落地且可验证”
- **代码层面**
  - 替换所有 `String.repeat` 为 Java 8 兼容实现（新增 `StringUtils.repeat(...)` 或复用现有 util）。
  - 替换 `Field.canAccess` 等 Java 9+ 反射 API，为 Java 8 兼容写法（`isAccessible()/setAccessible(true)` 的防御式逻辑）。
  - 以“最小行为变更”为原则：只替换 API 依赖，不改动命令输出的核心语义。
- **构建层面（防回归）**
  - 推荐采用组合策略：
    1) **API 签名校验（强约束）**：引入 Animal Sniffer（Java 8 signature）或等价机制，保证在 JDK 11+ 编译也能发现调用了 Java 9/11 API；
    2) **编译器 release（可选增强）**：在 JDK 9+ 环境启用 `--release 8`（通过 Maven profile `jdk9plus`），进一步降低“误用新 API”的概率；
  - 目标：让“JDK11 编译 + Java8 运行炸”的问题在构建阶段必现。

#### 2) `jad`：把目标 JVM bytecode 真实喂给 CFR
- 当前实现已能获取 class bytecode，但 CFR 分析入口未接收 bytecode。
- 方案：在 Agent 侧将 bytecode 写入临时目录的 `.class` 文件（按包路径落盘），再将**文件路径**传入 `CfrDriver#analyse(...)`。
  - 优点：实现简单、与 CFR 原生模型一致
  - 风险：临时文件清理、并发反编译的文件隔离
  - 缓解：使用 `Files.createTempDirectory(...)` + `try/finally` 递归清理；按 traceId/jadId 隔离
- 可选增强：若需要支持 inner/匿名类，补齐关联 class 的 bytecode 写入（按需）。

#### 3) `session`：缓存隔离 + 输出脱敏
- **缓存层**
  - `session` 命令应默认不可缓存（其输出强依赖连接上下文且包含敏感 token）。
  - 额外防御：缓存 key 增加 client 维度（如 `clientId`），避免未来有其他“上下文相关命令”误标 cacheable 造成串线。
- **输出层**
  - 默认输出脱敏 token（例如只显示前后若干位），避免复制粘贴与日志侧泄露。
  - 提供显式参数（如 `session --show-token`）才输出完整 SessionId。
- **审计层（可选）**
  - 对输出完整 token 的行为写审计（便于追踪敏感信息访问）。

#### 4) wildcard/regex：明确语义 + 抗 ReDoS
- **通配符（默认）**
  - 全部命令默认将用户输入视为“仅 `*` 特殊”的 wildcard；
  - 统一使用 `WildcardMatcher`（已存在）将 wildcard 转换为安全 Pattern（会转义其他正则元字符）。
  - 匹配语义建议收敛为 `matches`（全字符串匹配），用户需要模糊匹配时使用 `*Foo*`。
- **正则（`-E`）**
  - 若用户确认允许引入 `re2j`：`-E` 使用 re2j 编译与匹配，避免灾难性回溯。
  - 若用户不允许新依赖：对 `-E` 做保守策略组合：
    - 权限收敛（仅 ADMIN 或通过配置显式开启）
    - 输入长度限制与黑名单（禁止回溯高风险构造）
    - 捕获 `PatternSyntaxException` 并返回可读错误（而非抛异常导致命令失败）

#### 5) watch/tt：值快照替代强引用留存
- 引入“值快照（snapshot）”模型：采集时将参数/返回值/异常转换为**安全摘要对象**（仅包含受限长度的字符串摘要与必要类型信息），避免把原始对象图强引用进队列/ring buffer。
- 兼容性策略：
  - 对基础类型/字符串/枚举：可保留可复现值；
  - 对复杂对象：仅保留摘要 + 类型信息，回放模板输出 `null /* summary */`；
  - 保持 `SleuthValueFormatter` 的深度/长度限制作为摘要生成的统一入口。

#### 6) trace：采样语义一致 + 去重复
- **采样语义修复**：采样以“根调用”为单位：
  - 根调用决定是否采样；
  - 子调用继承根采样结果（父未采样则子不采样），避免碎片化树。
- **去重复策略（同一 traceId）**
  - 对“已被当前 trace enhancer 插桩的方法调用”，避免同时记录 SUB_METHOD_CALL 与 Node。
  - 可实现方式：在 `TraceEnhancer` 的 `visitMethodInsn` 中对 owner/name/desc 与本 enhancer 的匹配范围做判定，命中则跳过 SUB_METHOD_CALL 注入。
- 可选增强：增加配置开关允许关闭 SUB_METHOD_CALL（只保留 traced 方法树），进一步降低开销。

#### 7) stdout/stderr 污染治理
- 将 `PerformanceOptimizer` 与 `MetricsCollector` 等模块中直接 `System.out/err` 打印的行为统一受 `logging.performance.enabled` 控制：
  - 默认保持现状，但在生产建议设置为 `false`；
  - 对关键告警可改为写入审计/安全日志（而非 stdout）。

## Architecture Decision ADR

### ADR-001: 维持 Java 8 运行时兼容为项目基线
**Context:** 项目文档/知识库声明 Java 8，且诊断工具常用于较老运行环境；当前实现存在 Java 9/11 API 依赖导致兼容性失真。
**Decision:** 以 Java 8 运行时为基线，移除 Java 9/11 API 依赖，并在构建阶段引入 API 兼容性校验防回归。
**Rationale:** 最大化部署覆盖面；并通过构建约束让兼容性问题可提前暴露。
**Alternatives:** 升级最低版本到 Java 11 → 拒绝原因：与“Java 8+”声明冲突，且会直接淘汰部分目标 JVM 用户。
**Impact:** 需要维护少量兼容层工具方法；构建过程增加校验步骤但可降低运行时事故。

### ADR-002: `-E` 正则的抗 ReDoS 策略（待确认）
**Context:** Java 原生正则存在灾难性回溯风险，扫描 loaded classes/methods 时可能拖垮目标 JVM。
**Decision:** 待用户确认是否引入 `re2j`。若允许，引入后 `-E` 走 re2j；若不允许，收敛权限与输入规则，默认不对低权限开放任意 regex。
**Rationale:** 目标 JVM 安全与稳定优先；避免把诊断工具变成 DoS 放大器。
**Alternatives:** 继续使用 `java.util.regex.Pattern` 且不加限制 → 拒绝原因：风险不可接受。
**Impact:** 可能导致 `-E` 行为变更或权限提升要求，需要文档说明。

## Security and Performance
- **Security:**
  - `session` 输出脱敏与不可缓存，避免 token 泄露；
  - `-E` regex 若继续使用 JDK Pattern，必须做权限与输入限制；推荐 re2j。
  - 缓存 key 增加 client 维度，防止跨连接串线。
- **Performance:**
  - watch/tt 值快照避免强引用大对象，降低内存与 GC 压力；
  - trace 采样继承与去重复减少事件量；
  - stdout/stderr 输出可配置关闭，避免生产日志污染与额外 IO 开销。

## Testing and Deployment
- **Testing:**
  - 单测覆盖：Java 8 兼容工具方法、`jad` 反编译路径、session 缓存隔离与脱敏、wildcard/regex 行为、trace 采样继承与去重复、watch/tt 快照不强引用复杂对象。
  - 构建期校验：引入 API 兼容性校验插件并在默认生命周期执行。
- **Deployment:**
  - 对外文档更新（README/知识库）同步说明：Java 8 运行时基线、`session --show-token`、`-E` 风险与策略、`logging.performance.enabled` 的推荐配置。
