# 变更提案: Arthas 核心能力补齐（Stack 追踪 + TT Replay Lite）

## 需求背景
Java-Sleuth 的目标定位与 Arthas 类似：在不重启目标 JVM 的前提下，提供在线诊断与动态观察能力（watch/trace/monitor/tt 等）。

当前项目虽然已经具备大量 Arthas 同名/近似命令，但仍存在两类“核心能力空缺/不一致”问题：
1. `stack` 命令语义不对齐：目前更偏向于“热点栈采样/监控”，而 Arthas 的 `stack` 侧重于“方法触发时打印调用栈”，便于定位“是谁在调用某个方法”。
2. `tt` 缺少 replay 能力：项目已经能记录调用参数/返回值/异常，但缺少“把记录转换成可复现动作”的能力。

用户期望：实现类似 Arthas 的核心功能，但坚持简化实现（不引入 OGNL/脚本引擎，不追求完整可执行 replay）。

## 变更内容
1. 为 `stack` 增加“Arthas 风格”的方法调用栈追踪模式（保留现有 `stack monitor ...` 兼容行为）。
2. 为 `tt` 增加 `replay` 的 Lite 版本：生成可复现/可拷贝的调用信息与代码模板（默认不在目标 JVM 里执行，避免高风险副作用）。
3. 补齐插桩健壮性验证：新增单测覆盖“返回值/异常路径不被插桩破坏”等关键字节码语义。

## 影响范围
- **模块:** command / enhancement / monitor / data / util / security
- **文件:** 预计涉及 8-15 个 Java 文件 + 方案包文档
- **APIs:** 无对外网络 API，命令行/交互协议维持兼容（新增子命令/参数）
- **数据:** 新增/扩展内存内事件结构（stack 事件、tt replay 输出），不引入持久化

## 核心场景

### Requirement: Stack（方法调用栈追踪）
**Module:** command + enhancement + monitor
为 `stack` 增加方法追踪模式，用于回答“是谁调用了这个方法？”。

#### Scenario: 追踪某个方法的调用来源
在目标 JVM 中，当 `com.example.Service.doWork(..)` 被调用时输出调用栈摘要。
- 支持 `-n` 限制输出次数，`-t` 超时自动结束
- 支持 `--depth` 限制栈深度，避免刷屏
- 支持 `--bg` 后台运行，通过 `jobs` 管理输出
- 仍保留 `stack monitor start|stop|status` 的原有行为

### Requirement: TT Replay Lite（生成复现模板）
**Module:** command + util + data
为 `tt` 增加轻量 replay：把指定 record 的信息转换成“可复现描述/代码模板”。

#### Scenario: 基于 tt 记录生成复现代码骨架
用户执行 `tt replay <recordId>` 后输出：
- 目标类/方法签名
- 参数摘要（对字符串/数字/布尔等基础类型尽量输出可复现字面量；复杂对象输出安全摘要）
- 复现代码模板（不保证可直接运行，但能指导开发者快速写复现用例）
- 默认不在目标 JVM 内执行 replay（如需执行，必须另行设计并受权限控制）

### Requirement: 插桩语义健壮性（返回值/异常不破坏）
**Module:** enhancement + test
新增单元测试验证：
- 对有返回值的方法插桩后返回值保持一致
- 对抛异常的方法插桩后异常类型与堆栈行为保持一致
- 覆盖基础返回类型（对象/基本类型/void）与异常路径

## 风险评估
- **风险:** 方法级 stack 追踪会带来明显性能开销（获取堆栈较重），并可能产生大量输出。
  **缓解:** 默认要求 `-n/-t`，提供 `--depth`，支持采样/限流（必要时复用 ProductionConfig 的采样率）。
- **风险:** `tt replay` 若直接执行可能导致副作用或安全风险。
  **缓解:** 本轮仅实现“生成模板”，不在目标 JVM 执行；若后续要执行，需 admin 权限 + 明确风险提示。
- **风险:** 新增插桩路径可能引入字节码验证/栈语义问题。
  **缓解:** 增加可重复运行的 ASM/JVM 级单测覆盖关键路径，并在 CI/本地执行 `mvn test`。
