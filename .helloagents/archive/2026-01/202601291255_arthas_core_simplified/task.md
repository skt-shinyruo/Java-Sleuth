# 任务清单：Arthas 核心能力（简化增强版）

Directory: `helloagents/plan/202601291255_arthas_core_simplified/`

---

## 1. 基础命令补齐（stop/session/perm/version/options）
- [√] 1.1 新增 `stop` 命令（优雅停止 agent）：新增 `src/main/java/com/javasleuth/command/impl/StopCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`，验证 why.md#requirement-管理与诊断命令补齐stopsessionpermversionoptionsloggerdumpgetstatic
- [√] 1.2 新增 `session` 命令（会话摘要）：新增 `src/main/java/com/javasleuth/command/impl/SessionCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`，验证 why.md#scenario-快速查看会话与权限
- [√] 1.3 新增 `perm` 命令（权限摘要）：新增 `src/main/java/com/javasleuth/command/impl/PermCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`，验证 why.md#scenario-快速查看会话与权限
- [√] 1.4 新增 `version` 命令：新增 `src/main/java/com/javasleuth/command/impl/VersionCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`
- [-] 1.5 新增 `options` 命令（config 的轻量视图/别名）：新增 `src/main/java/com/javasleuth/command/impl/OptionsCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`（已由 `config show/get/set` 覆盖，暂不单独提供 options）

## 2. Watch/Trace（表达式/条件 + 强化 trace 聚合）
- [√] 2.1 增加 `--expr/--condition` 解析与 help 文案：更新 `src/main/java/com/javasleuth/command/impl/WatchCommand.java`，验证 why.md#requirement-watchtrace-expression--condition
- [√] 2.2 将 `--expr/--condition` 同步到 `trace`：更新 `src/main/java/com/javasleuth/command/impl/TraceCommand.java`，验证 why.md#requirement-watchtrace-expression--condition
- [√] 2.3 新增 trace invocation 聚合器（按单次调用成树输出，统计 root cost）：新增 `src/main/java/com/javasleuth/monitor/TraceAggregator.java`（或等价命名）并在 `src/main/java/com/javasleuth/command/impl/TraceCommand.java` 接入，验证 why.md#requirement-watchtrace-expression--condition
- [-] 2.4（可选受限）对子调用 cost 统计的实验性实现（默认关闭，通过选项开启）：更新 `src/main/java/com/javasleuth/enhancement/TraceEnhancer.java` + `src/main/java/com/javasleuth/monitor/TraceInterceptor.java`，并增加保护阈值（depth/limit），验证 why.md#requirement-watchtrace-expression--condition

## 3. Reset（全量回滚增强）
- [√] 3.1 增加 transformer 的“已增强类快照”能力：更新 `src/main/java/com/javasleuth/enhancement/SleuthClassFileTransformer.java`，验证 why.md#requirement-reset
- [√] 3.2 实现 `reset` 的回滚流程（停止会话 + 清 enhancer + retransform）：新增 `src/main/java/com/javasleuth/command/impl/ResetCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`，验证 why.md#scenario-one-shot-rollback-all-enhancers

## 4. TT-lite（record/list/detail，不做 replay）
- [√] 4.1 定义 tt-lite 的记录数据结构：新增 `src/main/java/com/javasleuth/data/TtRecord.java`（或等价命名），验证 why.md#scenario-record-invocations-and-query
- [√] 4.2 增加 tt-lite 的 interceptor/enhancer 与 ring buffer：新增 `src/main/java/com/javasleuth/monitor/TtInterceptor.java` 与 `src/main/java/com/javasleuth/enhancement/TtEnhancer.java`（或等价命名），验证 why.md#requirement-tt-lite-recordlistdetail
- [√] 4.3 新增 `tt` 命令（start/list/detail/stop）：新增 `src/main/java/com/javasleuth/command/impl/TtCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`，验证 why.md#requirement-tt-lite-recordlistdetail

## 5. Jobs（后台任务管理）
- [√] 5.1 实现 `JobManager`（任务注册、状态、输出 ring buffer、stop）：新增 `src/main/java/com/javasleuth/command/JobManager.java`（或等价位置），验证 why.md#requirement-jobs-background-commands
- [√] 5.2 实现 `jobs` 命令（list/tail/stop）：新增 `src/main/java/com/javasleuth/command/impl/JobsCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`，验证 why.md#scenario-starttailstop-background-watch
- [√] 5.3 为 `watch/trace/tt` 增加 `--bg` 选项并接入 JobManager：更新 `src/main/java/com/javasleuth/command/impl/WatchCommand.java`、`src/main/java/com/javasleuth/command/impl/TraceCommand.java`、`src/main/java/com/javasleuth/command/impl/TtCommand.java`，验证 why.md#scenario-starttailstop-background-watch

## 6. 诊断增强命令（logger/dump/jad-contains/getstatic/monitor/vmoption/thread/mc）
- [√] 6.1 新增 `logger` 命令（java.util.logging，list/get/set）：新增 `src/main/java/com/javasleuth/command/impl/LoggerCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`，验证 why.md#scenario-动态调整日志级别简化版
- [√] 6.2 新增 `dump` 命令（导出类字节码）：新增 `src/main/java/com/javasleuth/command/impl/DumpCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`，验证 why.md#scenario-导出类字节码
- [√] 6.3 增强 `jad` 输出过滤（--contains/--max-lines）：更新 `src/main/java/com/javasleuth/command/impl/JadCommand.java`
- [√] 6.4 新增 `getstatic` 命令（只读静态字段摘要）：新增 `src/main/java/com/javasleuth/command/impl/GetStaticCommand.java` + 注册到 `src/main/java/com/javasleuth/command/BuiltinCommandProvider.java`，验证 why.md#scenario-读取静态字段摘要ognl-lite只读
- [√] 6.5 实现 `monitor`（Arthas monitor 简化版）：新增 `src/main/java/com/javasleuth/monitor/MonitorInterceptor.java` 与 `src/main/java/com/javasleuth/enhancement/MonitorEnhancer.java`
- [√] 6.6 接入并替换现有 `monitor` 命令为可插桩实现（支持 -i/-n/--bg）：更新 `src/main/java/com/javasleuth/command/impl/MonitorCommand.java` + 注册/权限元数据调整
- [√] 6.7 增强 `vmoption`（HotSpotDiagnosticMXBean list/get/set）：更新 `src/main/java/com/javasleuth/command/impl/VmOptionCommand.java` + 权限/审计策略调整（set=admin+dangrous）
- [√] 6.8 增强 `thread`：支持 `thread -n <N> -i <ms>` top CPU delta：更新 `src/main/java/com/javasleuth/command/impl/ThreadCommand.java`
- [√] 6.9 修复并增强 `mc` 编译产物可靠性：更新 `src/main/java/com/javasleuth/compiler/MemoryJavaCompiler.java` 与 `src/main/java/com/javasleuth/command/impl/MemoryCompilerCommand.java`

## 7. Util（安全格式化与条件评估）
- [√] 7.1 新增 `--condition` 解析与评估（白名单字段 + 简单比较）：新增 `src/main/java/com/javasleuth/util/SleuthConditionEvaluator.java`，验证 why.md#requirement-watchtrace-expression--condition
- [√] 7.2 新增安全可读化 formatter（限长/限深/脱敏）：新增 `src/main/java/com/javasleuth/util/SleuthValueFormatter.java`，验证 why.md#requirement-watchtrace-expression--condition
- [√] 7.3 将 watch/tt/getstatic 输出改为使用 formatter：更新 `src/main/java/com/javasleuth/data/WatchResult.java` 与 `src/main/java/com/javasleuth/command/impl/WatchCommand.java`（getstatic 在自身命令内调用），验证 why.md#requirement-watchtrace-expression--condition
- [√] 7.4 新增通用 ring buffer（jobs/tt 复用）：新增 `src/main/java/com/javasleuth/util/RingBuffer.java`（或等价命名）

## 8. Security Check
- [?] 8.1 执行安全检查（按 G9：输入校验、敏感信息处理、权限控制、EHRB 风险规避），重点覆盖 `--expr/--condition`、trace 聚合、tt 输出、jobs tail 输出、dump/getstatic/logger set、vmoption set、monitor、mc 输出

## 9. Documentation Update（知识库同步）
- [√] 9.1 更新模块文档：`helloagents/wiki/modules/command.md`（新增 stop/session/perm/version/options/reset/tt/jobs/logger/dump/getstatic/monitor/vmoption/thread/mc 与参数）
- [√] 9.2 更新模块文档：`helloagents/wiki/modules/enhancement.md`（reset/trace/tt 的 enhancer 与回滚能力）
- [√] 9.3 更新模块文档：`helloagents/wiki/modules/monitor.md`（trace 聚合、tt 拦截器）
- [√] 9.4 更新模块文档：`helloagents/wiki/modules/data.md`（TtRecord/输出字段）
- [√] 9.5 更新模块文档：`helloagents/wiki/modules/util.md`（condition/formatter/ring buffer）
- [√] 9.6 更新模块文档：`helloagents/wiki/modules/compiler.md`（mc 编译产物修复与使用建议）
- [√] 9.7 更新变更记录：`helloagents/CHANGELOG.md`

## 10. Testing（最小必要验证）
- [√] 10.1 增加单元测试：为 condition evaluator、formatter、ring buffer 新增 `src/test/java/...` 测试用例（JUnit4）
- [√] 10.2 增加单元测试：为 trace 聚合器新增最小用例（模拟事件序列生成树输出）
- [?] 10.3 增加单元测试：为 monitor 统计、vmoption get/list、mc 编译产出新增最小用例
- [?] 10.4 手工验证：启动 `com.javasleuth.test.EnhancedTestApplication`，attach 后验证 `watch/trace --expr/--condition`、强化 trace 输出、`tt`、`jobs`、`reset`
- [?] 10.5 手工验证：验证 `stop/session/perm/version/options/logger/dump/getstatic` 的基本可用性与权限限制
- [?] 10.6 手工验证：验证 `monitor`（周期输出/--bg/stop/reset）、`vmoption set`（可写项）、`thread -n/-i`、`mc` 产物可用于 redefine
