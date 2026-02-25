# 变更提案: bootstrap-bridge-separation

## 元信息
```yaml
类型: 重构
方案类型: implementation
优先级: P1
状态: 已确认
创建: 2026-02-25
```

---

## 1. 需求

### 背景
当前 `agent` 产物为 `jar-with-dependencies`（fat-jar），并且在 `com.javasleuth.agent.SleuthAgent` 启动时将“自身 jar”通过 `Instrumentation#appendToBootstrapClassLoaderSearch` 追加到 BootstrapClassLoader 搜索路径。

这会导致 Bootstrap 可见域被动扩大：除了 `com.javasleuth.bootstrap.*` 桥接层之外，`com.javasleuth.agent.*` 也进入了 bootstrap 可见域。结果是：

- 意外类可见性扩大（bootstrap 域内可见的类越多，越容易被误用/误依赖）。
- 演进约束上升：任何未来放进 agent jar 的类，都可能被视为“bootstrap 可见 API”。
- 与项目既定目标“bootstrap 暴露面最小化”冲突。

### 目标
- 产物层面分离 **bootstrap-bridge 最小 jar** 与 **agent 引导 jar**。
- 运行时仅将 bridge jar append 到 bootstrap（仅包含 `com.javasleuth.bootstrap.*`）。
- 确保 `com.javasleuth.agent.*` 不再进入 bootstrap 可见域（仍在系统/应用侧可见）。
- 保持 attach/premain 链路与 container 隔离加载机制不变（失败仍 fail-fast，保护目标 JVM）。

### 约束条件
```yaml
时间约束: -
性能约束: append 与 jar 定位需轻量（仅启动期执行）
兼容性约束: Java 8；保持现有 `*-jar-with-dependencies.jar` 命名约定不变（脚本/定位逻辑依赖）
业务约束: bootstrap 模块继续保持 JDK-only（禁止引入依赖）
```

### 验收标准
- [ ] `SleuthAgent` 不再 append “agent 自身 fat-jar”，而是 append 单独的 bridge jar
- [ ] 被 append 的 jar 不包含 `com/javasleuth/agent/`（运行时校验 + 失败提示）
- [ ] `com.javasleuth.bootstrap.agent.CoreClassLoaderRegistry` 与 `com.javasleuth.bootstrap.monitor.TraceInterceptor` 可被 BootstrapClassLoader 加载（`ClassLoader == null`）
- [ ] `com.javasleuth.agent.BootstrapAttachGate` 不可被 BootstrapClassLoader 加载（验证 bootstrap 可见面未扩大）
- [ ] `mvn test` 通过；并且 `agent/target/` 下产出 `java-sleuth-bootstrap-bridge-<version>.jar`

---

## 2. 方案

### 技术方案
方案采用“构建产物拆分 + 运行时仅 append bridge jar”的方式：

1. **构建侧**：在 `agent` 模块打包阶段，将 `bootstrap` 模块的 jar 复制为 `java-sleuth-bootstrap-bridge-${project.version}.jar`，并保证其与 agent jar 位于同一目录（默认 `agent/target/`）。
2. **运行时**：`SleuthAgent` 启动时定位 bridge jar 并 append 到 bootstrap；随后再通过 bootstrap classloader 反射调用 bootstrap 工具类（`JarLocator`/`SystemPropertyRollbackRegistry`），避免 agent 与 bootstrap 的编译期耦合、避免误触发 system classloader 加载 bootstrap 版本导致 SSOT 分裂。

### 影响范围
```yaml
涉及模块:
  - agent: 调整 bootstrap append 行为；调整构建产物（复制 bridge jar）
  - bootstrap: 作为 bridge jar 来源（不引入新依赖，保持 JDK-only）
  - (KB) modules/agent.md & modules/bootstrap.md: 同步产物与边界说明
预计变更文件: 4-8
```

### 风险评估
| 风险 | 等级 | 应对 |
|------|------|------|
| 运行时找不到 bridge jar，导致 fail-fast | 中 | 构建期复制 bridge jar 到 `agent/target/`；运行时支持 override（sysprop/env）；错误提示明确给出修复路径 |
| append 失败（权限/环境限制） | 中 | 维持现有 fail-fast 策略；提示 operator 检查 append 是否成功 |
| 反射调用 bootstrap 工具类失败（类名/签名变化） | 低 | 使用固定类名+方法签名；失败时输出清晰错误并 fail-fast |

---

## 3. 技术设计（可选）

> 涉及架构变更、API设计、数据模型变更时填写

### 架构设计
```mermaid
flowchart TD
    A[Agent 引导 jar\ncom.javasleuth.agent.*] -->|append| B[BootstrapClassLoader\n仅 bridge jar]
    B --> C[隔离 URLClassLoader(parent=null)\n加载 container fat-jar]
    C --> D[core/runtime]
```

### API设计
无（内部启动链路调整，无对外 API 变更）

---

## 4. 核心场景

> 执行完成后同步到对应模块文档

### 场景: Agent 启动时追加 bootstrap bridge
**模块**: agent/bootstrap
**条件**: agent jar 可定位到 `java-sleuth-bootstrap-bridge-*.jar`（或通过 override 指定）
**行为**: `SleuthAgent` 调用 `Instrumentation#appendToBootstrapClassLoaderSearch(bridgeJar)`，并校验关键 bridge 类可由 BootstrapClassLoader 加载
**结果**: 增强器可安全注入 `com.javasleuth.bootstrap.*` 调用；bootstrap 可见域不包含 `com.javasleuth.agent.*`

---

## 5. 技术决策

> 本方案涉及的技术决策，归档后成为决策的唯一完整记录

### bootstrap-bridge-separation#D001: 以“独立 bridge jar”替代 append agent fat-jar
**日期**: 2026-02-25
**状态**: ✅采纳
**背景**: 需要最小化 bootstrap 可见面，避免将 `com.javasleuth.agent.*` 暴露到 bootstrap 域
**选项分析**:
| 选项 | 优点 | 缺点 |
|------|------|------|
| A: 继续 append agent fat-jar（现状） | 最省事 | bootstrap 可见面扩大；未来演进约束高 |
| B: 构建产物拆分，运行时仅 append bridge jar（本方案） | bootstrap 暴露面最小；边界清晰 | 需要额外 bridge jar 产物与定位逻辑 |
| C: 运行时从 self jar 动态抽取 bootstrap-only jar 再 append | 单 jar 分发友好；仍可最小化 | 运行时 IO/临时文件复杂度更高，维护成本上升 |
**决策**: 选择方案 B
**理由**: 以最小工程复杂度获得清晰边界；且能通过构建期产物保证 bridge jar 可用
**影响**: agent 模块打包与启动链路；KB 文档同步说明产物与边界
