# Change Proposal: bootstrap/foundation/core 模块包根前缀化（消除 split package）

## Requirement Background
当前 `bootstrap/foundation/core` 存在典型的 “split package（分裂包）” 问题：同一个包名 `com.javasleuth.util` 同时出现在 `bootstrap`、`foundation` 以及 `core` 中。

这会带来两类直接风险：
1. **边界不显式**：从 import 语义上无法判断“某个 util 类到底是否 bootstrap 可见”，导致 bootstrap 可见面在命名层面不可控、不可审计。
2. **类加载域泄漏**：`core` 的实现细节可能被“顺手复用”到 bootstrap 侧（例如 `JobManager` 直接依赖 bootstrap 的 `RingBuffer`），把本应只在 isolated core 域可见的实现细节推到最敏感的 bootstrap 域，增加兼容性与冲突面。

结合本项目的隔离加载目标（bootstrap → isolated core），需要用 **包根命名约束** 把边界变为“显式可见”，并彻底消除 split package 造成的解析不确定性与维护成本。

## Change Content
1. 为 `bootstrap/foundation/core` 引入 **模块前缀包根**，将三个模块中现有的 `com.javasleuth.*` 包分别迁移到：
   - `bootstrap` → `com.javasleuth.bootstrap.*`
   - `foundation` → `com.javasleuth.foundation.*`
   - `core` → `com.javasleuth.core.*`
2. 彻底消除 `com.javasleuth.util` 在多个模块同时存在的 split package。
3. 收敛 bootstrap 可见面：所有会被 append 到 BootstrapClassLoaderSearch 的桥接/spy 类型必须位于 `com.javasleuth.bootstrap.*`（命名即边界）。
4. `core` 不再复用 bootstrap 的实现细节（如 ring buffer 等），需要时在 `com.javasleuth.core.*` 内提供 core 内聚实现。

## Impact Scope
- **Modules:** bootstrap、foundation、core、agent、launcher（仅因 import/字符串引用/manifest 入口更新）
- **Files:** 预计大范围改动（包声明、目录结构、import、常量字符串/ASM 内部名、测试包名）
- **APIs:** 对外命令协议/命令名称不变；受影响为内部类名与包名（含 agent 启动入口字符串、ASM 生成的调用目标）
- **Data:** 无数据模型/存储结构变更

## Core Scenarios

### Requirement: R1 - Split package 消除 + Bootstrap 边界显式化
**Module:** bootstrap / foundation / core
以模块前缀包根将边界固化到命名层面，避免同名包跨域存在。

#### Scenario: 编译期与代码审计层面可验证
前置：完成包迁移并通过构建  
- `bootstrap/foundation/core` 的 main 源码中不再出现重复的 `package com.javasleuth.util;`
- bootstrap 可见类统一位于 `com.javasleuth.bootstrap.*`，从 import 即可判断边界归属

### Requirement: R2 - core 不依赖 bootstrap 的实现细节
**Module:** core
core 内部实现（jobs 等）不应依赖任何 bootstrap util 的具体实现，以避免类加载域泄漏与边界膨胀。

#### Scenario: JobManager 不再引用 bootstrap RingBuffer
前置：包迁移完成  
- `core` 侧不再存在对 `com.javasleuth.bootstrap.util.RingBuffer` 的依赖
- `JobManager` 等 core 组件使用 `com.javasleuth.core.*` 范围内的实现

### Requirement: R3 - 增强字节码引用同步迁移
**Module:** core（enhancement）/ bootstrap（monitor）
增强代码生成出的调用目标必须与新的 bootstrap 包名一致，确保 attach 后实际运行路径仍可达。

#### Scenario: 增强调用仍可正常触发 monitor/tt/watch 逻辑
前置：attach → agentmain 启动成功  
- ASM 生成的 owner/internalName 由旧包名更新为 `com/javasleuth/bootstrap/...`
- 运行时无 `NoClassDefFoundError`/`LinkageError` 等类加载错误

## Risk Assessment
- **Risk:** 大范围包名迁移可能引入编译失败、运行时找不到类（字符串常量/反射/ASM internalName 漏改）  
  **Mitigation:** 分阶段迁移（bootstrap→foundation→core）、每阶段运行 `mvn test`，并通过全仓库扫描定位字符串/ASM 引用。
- **Risk:** bootstrap 可见 jar 当前由 agent fat-jar append，迁移后可能意外扩大 bootstrap 暴露面  
  **Mitigation:** 将“bootstrap 可见包根白名单”固化为规则，并在实现阶段加入自检脚本/测试防回归（例如扫描 bootstrap jar 中的包根是否只有 `com.javasleuth.bootstrap.*` + 必要的 agent 入口）。
