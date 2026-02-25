# Task List: Java 8 兼容性与诊断稳定性修复（jad/session/regex/trace/watch/tt）

Directory: `helloagents/plan/202602011706_core_fixes_java8_jad_session_regex_trace/`

---

## 1. Java 8 兼容与构建防回归
- [√] 1.1 替换所有 `String.repeat` 用法为 Java 8 兼容实现（新增/复用 `StringUtils.repeat(...)`），覆盖 `ThreadCommand/MonitorCommand/JadCommand` 等，verify why.md#requirement-java-8-运行时兼容与构建防回归
- [√] 1.2 替换 `Field.canAccess` 为 Java 8 兼容写法（`isAccessible/setAccessible` 防御式逻辑），覆盖 `GetStaticCommand`，verify why.md#requirement-java-8-运行时兼容与构建防回归
- [√] 1.3 在 `pom.xml` 增加构建期 Java 8 API 兼容性校验（Animal Sniffer，合理忽略 Attach API），verify why.md#requirement-java-8-运行时兼容与构建防回归
- [√] 1.4 增补单测/构建校验：确保在 JDK 11+ 编译也能捕获 Java 9/11 API 误用，verify why.md#requirement-java-8-运行时兼容与构建防回归

## 2. `jad` 反编译可用性修复
- [√] 2.1 修复 `JadCommand`：将获取到的 bytecode 写入临时 `.class` 文件并将文件路径传给 `CfrDriver#analyse`，确保输出非空或给出明确错误，verify why.md#requirement-jad-输出稳定可用
- [√] 2.2 为 `jad` 增加单测：对内置测试类/生成类进行反编译并断言输出包含关键结构（class/method 声明），verify why.md#requirement-jad-输出稳定可用

## 3. `session` 泄露/串线修复
- [√] 3.1 将 `session` 命令标记为不可缓存，并将 `CommandPipeline` 缓存 key 增加 `clientId` 维度（防止未来误配置导致串线），verify why.md#requirement-session-不泄露不串线
- [√] 3.2 调整 `SessionCommand` 输出：默认脱敏 token；增加 `--show-token` 等显式参数输出完整 token，verify why.md#requirement-session-不泄露不串线
- [√] 3.3 增加单测：并发/多上下文下缓存不串线，token 默认脱敏且参数可显示完整值，verify why.md#requirement-session-不泄露不串线

## 4. wildcard/regex 稳定性与抗 ReDoS
- [√] 4.1 `sc/sm/retransform` 等命令统一使用 `WildcardMatcher`（转义元字符），并将匹配语义收敛为 `matches`（通过 `*` 实现模糊匹配），verify why.md#requirement-wildcardregex-稳定且抗-redos
- [ ] 4.2 `sm -E`（regex）策略落地（依赖用户确认 ADR-002）：
  - [√] 4.2.1 若允许引入 `re2j`：接入 re2j 作为 `-E` 引擎，verify why.md#requirement-wildcardregex-稳定且抗-redos
  - [-] 4.2.2 若不允许新依赖：限制 `-E` 权限/输入长度并对语法错误友好提示，verify why.md#requirement-wildcardregex-稳定且抗-redos
- [√] 4.3 增加单测：包含 `$` `[` `]` `(` `)` 等模式不崩溃；非法 regex 给出可读错误；性能回归（避免长时间卡顿），verify why.md#requirement-wildcardregex-稳定且抗-redos

## 5. watch/tt/trace 资源风险与语义修复
- [√] 5.1 为 `watch/tt` 引入“值快照”结构：采集阶段将参数/返回值/异常转为受限摘要对象，避免强引用复杂对象图；调整 `WatchResult/TtRecord` 与相关格式化逻辑，verify why.md#requirement-watchtttrace-对目标-jvm-影响可控
- [√] 5.2 修复 trace 采样语义：采样以根调用为单位，子调用继承父采样结果，避免碎片化树，verify why.md#requirement-watchtttrace-对目标-jvm-影响可控
- [√] 5.3 修复 trace 重复记录：对同一 traceId 内“已被插桩的方法调用”跳过 SUB_METHOD_CALL 注入（避免 SUB + Node 双份），verify why.md#requirement-watchtttrace-对目标-jvm-影响可控
- [√] 5.4 增加单测：trace 采样继承、去重复；watch/tt 快照不保留原始对象引用（至少对自定义大对象/对象图验证），verify why.md#requirement-watchtttrace-对目标-jvm-影响可控

## 6. stdout/stderr 污染治理
- [√] 6.1 将 `PerformanceOptimizer`/`MetricsCollector` 等 `System.out/err` 输出统一受 `logging.performance.enabled` 控制（默认更克制、可配置），verify why.md#requirement-watchtttrace-对目标-jvm-影响可控
- [√] 6.2 增加单测/最小验证：关闭配置后不产生控制台输出（通过默认配置与测试执行输出验证），verify why.md#requirement-watchtttrace-对目标-jvm-影响可控

## 7. Security Check
- [√] 7.1 执行安全检查（输入校验、敏感信息处理、权限控制、ReDoS 风险、缓存隔离），并对 `-E`/脱敏策略做回归验证

## 8. Documentation Update
- [√] 8.1 更新 README 与知识库（`helloagents/project.md` / `helloagents/wiki/*` 按需）：Java 8 运行时基线、`session --show-token`、`-E` 策略、`logging.performance.enabled` 推荐配置，verify why.md 全部 requirements
- [√] 8.2 更新 `helloagents/CHANGELOG.md`（记录修复项与潜在行为变更）

## 9. Testing
- [√] 9.1 运行 `mvn test` 并修复与本变更直接相关的失败用例
- [-] 9.2 针对 `jad/session/sc/sm/trace/watch/tt` 做最小手工回归（本地启动目标 JVM + attach + 执行命令）
