## 方案概述

本次采取“问题清单驱动”的渐进式改造：先完成配置/权限/打包/测试这些基础设施型修复，再视情况对命令 UX 做小步优化。整体原则是不做大规模架构改动，优先补齐“正确性 + 安全边界 + 可运维性 + 可测试性”。

## 技术策略与关键决策

1. **配置一致性治理**
   - 以 `ProductionConfig#setDefaults()` 作为“运行时默认行为”的事实来源，同时保证 `src/main/resources/sleuth-default.properties` 与其保持一致（便于运维阅读与覆盖）。
   - 对已经出现在默认配置文件、但代码不识别/不使用的 key（如 `production.*`）做明确取舍：
     - 方案 A：补齐实现（若确实需要 production-mode 的行为分支）
     - 方案 B：删除默认配置与文档中相关项，避免误导（若不准备实现）

2. **危险操作标注与审计/限流**
   - 在命令注册处（BuiltinCommandProvider / CommandMeta）补齐危险标记与审计要求，确保 AuthorizationManager 的权限条目能继承这些属性。
   - 对确有破坏性/高风险的命令增加更保守的默认策略（例如：需要审计、限流更严格、或在特定安全模式下拒绝执行）。
   - 同步调整 `perm`/`audit` 相关输出，让安全策略“可观察、可解释”。

3. **打包与启动体验**
   - 在 Maven assembly 的 manifest 中补齐 `Main-Class=com.javasleuth.launcher.SleuthLauncher`（仅针对 fat-jar），保持 `Premain-Class/Agent-Class` 等 agent 属性不变。
   - 兼容现有 `sleuth.sh/.bat`：脚本可继续使用 main class 方式启动；同时允许用户直接 `java -jar ...-jar-with-dependencies.jar`。

4. **测试补齐**
   - 新增单测聚焦“高风险分支与边界条件”，优先覆盖：
     - bind address 非回环 + security.mode=off → 拒绝启动
     - security.mode=hmac + secret 为空 → 拒绝启动
     - 协议/输入上限（文本行长度、frame payload）行为符合预期
     - 权限系统中危险标记与审计标记传递正确

5. **命令 UX 小修**
   - 对 `tt` replay 模板中的 TODO 占位做 UX 改造：输出中明确提示“仅生成模板/无法自动构造实例”的限制，并提供更可复制的模板结构（区分 static/instance）。
   - 对 `profiler` 中提及 “async-profiler” 的展示文案做校准（避免用户误以为依赖 native async-profiler）。

## 风险与回滚策略

- 风险：补齐 `dangerous/requiresAudit` 可能改变权限策略（更严格），导致部分现有脚本/习惯用法需要更高权限或显式确认。
  - 缓解：仅对显著高风险命令启用更严格标记，并在 release notes/文档中说明；必要时提供配置开关或兼容策略。
- 风险：修改 jar manifest 可能影响某些依赖 `java -jar` 行为的环境。
  - 缓解：仅新增 `Main-Class`，不移除 agent 相关属性；保持脚本不变，并新增自测验证。

