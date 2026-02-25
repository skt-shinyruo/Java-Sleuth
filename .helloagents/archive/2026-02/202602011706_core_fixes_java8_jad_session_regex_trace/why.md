# Change Proposal: Java 8 兼容性与诊断稳定性修复（jad/session/regex/trace/watch/tt）

## Requirement Background
当前项目在对外说明与知识库中宣称“Java 8 / Maven 3”，但实现与构建链路存在多处不一致与潜在安全/稳定性问题，集中体现在：

1. **Java 8 运行时兼容性不成立**：`pom.xml` 固定 `source/target=8`，但代码中使用了 Java 9/11 才提供的 API（例如 `String.repeat`、`Field.canAccess`）。这会导致：
   - 使用 JDK 8 编译：直接编译失败；
   - 使用 JDK 11+ 编译后在 Java 8 运行：可能出现 `NoSuchMethodError` 等运行时崩溃；
   - 当前未启用 `--release` 或 API 兼容性校验，CI/本地构建难以及早暴露问题。
2. **`jad` 反编译命令可靠性不足**：命令实现“看似读了 bytecode”，但未真正把 bytecode 传入 CFR 的分析入口，容易出现空输出或不可用。
3. **缓存策略存在安全与正确性漏洞**：`session` 命令输出 bearer token（SessionId），同时被标记为可缓存，且缓存 key 不含 client/session 维度，可能在短 TTL 内发生跨客户端串线/泄露。
4. **通配符/正则处理混用导致不稳定与 ReDoS 风险**：部分命令把通配符简单替换为正则（`* -> .*`），未转义其他元字符，可能触发 `PatternSyntaxException` 或灾难性回溯，扫描 `getAllLoadedClasses()` 时拖垮目标 JVM。
5. **插桩/采集对目标 JVM 资源影响偏大 & trace 语义不一致**：
   - `watch/tt` 记录中保留参数/返回值/异常的强引用，可能延迟 GC，甚至在大对象场景导致内存压力；
   - `trace` 的采样与子调用记录策略可能造成“父调用没采到、子调用采到”的碎片化树，或出现重复记录；
   - 部分监控/性能组件直接向目标 JVM stdout/stderr 打印，污染业务日志管道。

本变更目标是在**保持“Java 8 运行时兼容”**前提下，系统性修复以上问题，并补齐构建/测试/文档的防回归机制。

## Change Content
1. 统一 Java 8 兼容策略：移除 Java 9/11 API 依赖，补齐构建期兼容性校验（防止“编译通过但运行炸”）。
2. 修复 `jad`：确保 CFR 真实接收 `.class` 输入（来自目标 JVM bytecode），稳定输出反编译结果。
3. 修复 `session` 泄露/串线：调整缓存策略与输出脱敏，必要时增加按 client/session 维度隔离。
4. 统一 wildcard/regex 处理：默认走安全通配符匹配；对显式 regex 提供线性时间/可控风险方案，避免 ReDoS。
5. 降低插桩副作用：对 `watch/tt` 采集做“值快照”而非强引用留存；优化 `trace` 采样语义与重复记录；将性能/健康日志输出改为可配置。

## Impact Scope
- **Modules:** `command/*`, `enhancement/*`, `monitor/*`, `monitoring/*`, `util/*`, `security/*`, build (`pom.xml`)
- **Files:** 预计涉及多处命令实现、拦截器/数据结构、构建插件与配置默认值、部分测试用例与文档
- **APIs:** 无新增外部 API；命令行行为存在小幅语义收敛/脱敏输出变更
- **Data:** 无持久化数据变更

## Core Scenarios

### Requirement: Java 8 运行时兼容与构建防回归
**Module:** build / command / util
确保在 Java 8 运行时不会因 JDK 9/11 API 调用而崩溃，并且构建阶段能提前发现未来引入的新 API 依赖。

#### Scenario: 用 JDK 11+ 构建，Java 8 运行
- 产物在 Java 8 上可启动/可 attach/可执行基础命令
- 不出现 `NoSuchMethodError`（例如 `String.repeat`/`Field.canAccess`）

#### Scenario: 用 JDK 8 构建（可选支持）
- 若项目仍宣称可用 JDK 8 构建，则应编译通过；否则应在文档中明确构建最低版本并提供替代方案

### Requirement: `jad` 输出稳定可用
**Module:** command
`jad` 命令应稳定输出目标类的反编译源码（至少对常见 classpath 可达/目标 JVM 已加载类可用）。

#### Scenario: 反编译已加载类
- `jad com.example.Foo` 返回可读 Java 源码或明确错误（不是空输出“常态化”）
- 输出内容可被分页/截断策略正确处理（避免过大输出）

### Requirement: `session` 不泄露/不串线
**Module:** command / security / util
避免 session token 在缓存与输出中被错误复用或泄露，同时保证 session 信息显示可信。

#### Scenario: 多客户端并发执行 session
- 不同连接执行 `session` 不会互相拿到对方 sessionId/clientInfo/role
- 默认输出对 token 做脱敏（提供显式参数才显示完整 token）

### Requirement: wildcard/regex 稳定且抗 ReDoS
**Module:** command / util / security
把“通配符”与“正则”明确区分：默认通配符安全可预期；若允许正则，必须可控且不拖垮目标 JVM。

#### Scenario: 通配符包含正则元字符
- `sc com.foo.Bar$Inner*` 等包含 `$` `[` `]` `(` `)` 的模式不会抛 `PatternSyntaxException`
- 匹配语义与帮助文档一致（通配符仅 `*` 具有特殊含义）

#### Scenario: 输入恶意正则（若启用 -E）
- 不会导致长时间卡顿或 CPU 飙升（优先使用线性时间正则引擎；否则降级限制/权限控制）

### Requirement: watch/tt/trace 对目标 JVM 影响可控
**Module:** monitor / command / enhancement / util / monitoring
降低采集强引用与输出污染，优化 trace 语义一致性，提供可配置开关。

#### Scenario: watch/tt 采集大对象参数
- 记录不强引用保留大对象图（以“值快照/摘要”形式存储）
- 队列与 ring buffer 达到容量时行为可预期（丢弃/淘汰策略不导致额外内存攀升）

#### Scenario: trace 采样与树语义
- 采样以“根调用”为单位（父未采样则子不采样），避免碎片化树
- 对同一 traceId 的“已被 trace 的子方法调用”避免重复记录（SUB_METHOD_CALL + Node 双份）

#### Scenario: stdout/stderr 污染控制
- 性能/健康相关的 `System.out/err` 输出可通过配置关闭（默认面向生产更克制）

## Risk Assessment
- **Risk:** 行为收敛/输出变更（例如 pattern 匹配语义、`session` token 脱敏、trace 事件形态变化）可能影响依赖当前输出格式的脚本
  - **Mitigation:** 保留兼容参数（如 `session --show-token`），并在 README/知识库中明确变更点；新增回归测试覆盖关键命令输出
- **Risk:** 引入新依赖（可选：re2j）带来兼容性/CVE 风险
  - **Mitigation:** 依赖引入前做版本调研与 CVE 检查；提供不引入依赖的保守降级策略（权限/长度限制）
