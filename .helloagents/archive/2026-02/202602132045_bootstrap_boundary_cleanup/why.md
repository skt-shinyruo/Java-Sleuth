# Change Proposal: Bootstrap 边界收敛与重复实现去重

## Requirement Background
当前工程采用 `agent`（bootstrap）→ `core`（隔离 ClassLoader） 的两段式加载来规避依赖碰撞，这个方向本身是正确的。

但现状存在两个长期演进风险：

1. **Bootstrap 可见性边界过宽**
   - `agent` 产物为 `jar-with-dependencies`，且依赖 `foundation`。
   - `SleuthAgent` 在 attach 时会将自身 jar `appendToBootstrapClassLoaderSearch`。
   - 结果等价于把 `foundation` 下大量非 bootstrap 必需能力（如 `config/*`、`security/*`、`command/protocol/*`、大部分 `util/*`）提升为 bootstrap 可见，边界不清晰，后续易“误下沉/误上提”。

2. **关键逻辑存在重复实现**
   - jar 定位/marker 校验存在多处实现（`JarLocator`、`SleuthAgent`、`SleuthLauncher`），有漂移风险。
   - `agentArgs` → `System properties` 规则在 bootstrap 与 core 各自维护一份，后续新增参数/规则时容易不一致。

## Change Content
1. 引入新的 Maven 模块 `bootstrap`（JDK-only），作为 **唯一的 bootstrap-bridge（spy）类集合**：
   - 增强代码直接调用的拦截器：`com.javasleuth.monitor.*Interceptor`
   - 监控数据模型：`com.javasleuth.data.*`
   - 值快照/环形缓冲等轻量工具：`com.javasleuth.util.(RingBuffer|SleuthValueFormatter|SleuthValueSnapshotter|SleuthSnapshotValue)`

2. 拦截器从依赖 `ProductionConfig` 改为读取最小化的 bootstrap 配置（System properties），避免把完整配置系统拉入 bootstrap 可见范围。

3. 去重与 SSOT 收敛：
   - jar 定位/manifest marker 校验：统一由 `JarLocator` 提供（并对外暴露必要的 `isBootstrap/isCore` 判断）。
   - `agentArgs` 解析与 sysprop 落地：统一由一个公共工具类实现，bootstrap/core 共用。

## Impact Scope
- **Modules:**
  - 新增：`bootstrap`
  - 调整：`agent`、`foundation`、`core`、`launcher`
- **Files:**
  - 代码迁移：`foundation/src/main/java/com/javasleuth/{monitor,data,util}` → `bootstrap/src/main/java/com/javasleuth/...`
  - 去重重构：`agent/src/main/java/com/javasleuth/agent/SleuthAgent.java`、`core/src/main/java/com/javasleuth/agent/core/SleuthAgentCore.java`、`launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
  - Maven：根 `pom.xml` + 各子模块 `pom.xml`
  - 文档：`helloagents/wiki/modules/*`、`helloagents/CHANGELOG.md`

## Core Scenarios

### Requirement: Bootstrap 可见性最小化
**Module:** agent / bootstrap / foundation
确保被 append 到 bootstrap 的 jar 只包含“增强代码必需的桥接类”，避免把配置/安全/协议等能力误提升。

#### Scenario: agent attach 后 bootstrap 可见类收敛
前置：Launcher attach 成功  
- bootstrap 仅暴露 `monitor/data/snapshot` 等桥接类
- `foundation` 的 `config/security/command/protocol` 不再 bootstrap 可见

### Requirement: 关键规则单一来源（SSOT）
**Module:** bootstrap / agent / launcher / core
避免 jar 定位与 agentArgs 解析规则分叉。

#### Scenario: jar 定位与 agentArgs 规则统一
前置：不同启动路径（发布包/lib、IDE、cwd）  
- Launcher/Agent 使用同一套 jar marker 与定位逻辑
- bootstrap/core 使用同一套 agentArgs → sysprop 落地规则

## Risk Assessment
- **Risk:** 迁移模块后，类加载器边界与重复 class 解析可能出现行为差异（尤其是 `bootstrap` 与 `core` 同名类的优先级）。
  - **Mitigation:** 保持包名不变（FQCN 不变），并利用 `core` 隔离类加载器 parent=`null` 的 parent-first（bootstrap）解析路径确保同名类以 bootstrap 为准；通过 `mvn test` 与核心场景回归验证。
- **Risk:** 拦截器不再读取 `ProductionConfig` 可能造成监控策略与配置文件不一致。
  - **Mitigation:** 拦截器读取 `sleuth.*` sysprop，core 在启动时将相关监控配置同步落到 sysprop（sysprop 缺失时补齐），保持行为一致。

