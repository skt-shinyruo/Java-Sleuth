# 技术设计：Arthas 核心能力（简化增强版）

## 技术方案

### 核心技术
- Java 8（项目当前 target）
- JVM Attach API（`com.sun.tools.attach`）
- Java Instrumentation（retransform/redefine）
- ASM（字节码增强）
- JLine（交互式 CLI）
- CFR（`jad` 反编译）
- Jackson（metrics/audit JSON 输出）

### 实现要点

#### 1) `watch/trace` 的 `--expr` / `--condition`（受控、无脚本）
目标：实现 Arthas 常用能力，但不引入 OGNL/脚本执行。

**表达式（--expr）简化设计：**
- 仅支持预置字段（白名单），例如：
  - `cost`（耗时，ms 或 ns 统一到一个维度）
  - `params` / `params[i]`
  - `return`（返回值摘要）
  - `throw`（异常摘要）
  - `class` / `method` / `thread`
- 解析策略：逗号分隔，忽略空项，非法项报错并提示 help。

**条件（--condition）简化设计：**
- 语法：`<lhs>:<op>:<rhs>`（避免 `<` `>` `&&` 等被输入校验拦截）
- `op` 支持：`eq/ne/gt/ge/lt/le/contains/startswith/endswith/isnull/notnull`
- 支持多次 `--condition`，默认 AND 逻辑。
- 条件评估在“命令消费侧”进行（即从 interceptor 队列取出事件后再过滤），避免在被观测线程里做复杂逻辑。

**输出格式化（安全可读化）：**
- 新增统一 formatter：
  - 限制字符串长度、集合/Map 展示数量、对象字段展开数量与深度
  - 对疑似敏感字段名（password/secret/token/key 等）做脱敏
  - 避免递归/循环引用导致 OOM 或栈溢出（用 visited set + 深度限制）

#### 1.1) 强化 `trace`：按“单次调用”聚合成树，并统计 cost（简化）
目标：让 `trace` 更贴近 Arthas 体验（按一次调用输出一棵树，并展示耗时），但保持实现可控。

**简化聚合策略（建议优先实现）：**
- 以“目标方法的一次进入/退出（depth=0）”作为一个 invocation 边界：
  - `METHOD_ENTRY(depth=0)` 开始一个 invocation
  - 收集后续事件，直到对应的 `METHOD_EXIT/METHOD_EXCEPTION(depth=0)` 结束 invocation
- invocation 内部的事件按 `depth` 组织为树形文本输出。
- cost 统计：
  - 至少输出 root 方法的 `cost`（已有 `duration` 字段可用）
  - 可选增强：对子调用（SUB_METHOD_CALL）统计“调用次数”与“首次出现时间”，不做精确 cost（避免高开销）

**进阶聚合（可选，受限实现）：**
- 在 TraceEnhancer 的调用点周围插入轻量计时，输出子调用的 cost（受限：仅对前 N 个热点调用、或仅 depth<=K）。
- 需要更严格的性能保护（默认关闭，通过选项显式开启）。

#### 2) `reset`：全量撤销增强并回滚字节码
目标：像 Arthas 一样可快速“清场”，避免增强残留。

**关键点：**
- transformer 侧提供“当前已注册增强类列表”的快照能力。
- `reset` 流程：
  1. 停止所有 watch/trace/tt 会话（调用各 interceptor 的 unregisterAll）
  2. 清空 transformer enhancer map
  3. 对“已增强类列表”执行 `instrumentation.retransformClasses(...)`，触发恢复
  4. 输出统计：回滚类数量、清理会话数量、失败列表（如有）
- 权限：建议 `admin`（至少 `operator`），并记录审计。

#### 3) `tt-lite`：录制 + list/detail（不做 replay）
目标：提供低成本、高价值的调用复盘能力。

**简化设计：**
- 复用 watch 的增强思路（方法 entry/exit/exception）：
  - 新增 `TtEnhancer` + `TtInterceptor`（避免与 watch/trace 耦合）
  - `TtInterceptor` 将“完整调用”聚合为一条 `TtRecord`（包含：时间戳、线程、类方法、params 摘要、return/exception 摘要、cost）
- 存储：内存 ring buffer（每个 tt session 固定容量，例如 200 条，可配置）。
- 命令：
  - `tt start <class-pattern> <method-pattern> [options]`
  - `tt list [limit]`
  - `tt detail <id>`
  - `tt stop <sessionId|all>`
- 权限：`operator`（detail 输出可能敏感，建议不开放给 viewer）。

#### 4) `jobs`：后台任务模型（简化版）
目标：让 watch/trace/tt 可以后台执行并可管理。

**简化设计：**
- 新增 `JobManager`（进程内单例）：
  - 保存 job 元数据（id/name/commandLine/startTime/status）
  - 保存输出 ring buffer（按行或按 chunk），支持 tail
  - 支持 stop：触发任务中断 + 清理（必要时调用对应 stop/reset 逻辑）
- 命令：
  - `jobs` / `jobs list`
  - `jobs tail <id> [n]`
  - `jobs stop <id>`
- watch/trace/tt 增加 `--bg`（或 `--job`）选项：
  - 启动后立即返回 job id
  - 后台线程持续执行并写入 job 输出 buffer

#### 5) 管理与诊断命令补齐（stop/session/perm/version/options）
目标：补齐 Arthas 常用的管理/信息命令，降低排障摩擦。

**命令清单（简化版）：**
- `stop`：停止 agent 命令服务端并清理资源（优雅关闭）
- `session`：输出当前会话摘要（角色、鉴权/授权开关、协议模式等）
- `perm`：输出当前角色可执行命令列表摘要（复用 `AuthorizationManager.getPermissionsSummary`）
- `version`：输出版本与构建信息（至少包含项目版本号 + JVM 信息）
- `options`：以 Arthas 风格展示关键配置（可作为 `config show` 的别名视图）

**实现要点：**
- `stop`：从命令线程触发 `SleuthAgent.shutdown()`，避免阻塞可放入新线程；执行后当前连接将断开（属于预期行为）。
- `session/perm`：从 `CommandContextHolder` 取得 sessionId，并调用 `AuthenticationManager.validateSession` 与 `AuthorizationManager.getPermissionsSummary`。
- `options`：不新增复杂配置系统，直接复用 `ProductionConfig` 的读取与 runtime override（保持现状）。

#### 6) 诊断增强命令（logger/dump/jad-contains/getstatic）
目标：补齐 Arthas 常用诊断能力，保持安全与实现成本可控。

**6.1 `logger`（先支持 java.util.logging）：**
- `logger list [pattern] [limit]`
- `logger get <name>`
- `logger set <name> <level>`
实现建议：
- 使用 `java.util.logging.LogManager` 枚举 logger
- level 仅允许白名单：`SEVERE|WARNING|INFO|CONFIG|FINE|FINER|FINEST|OFF`
- 权限建议：至少 operator（更稳妥可设 admin）

**6.2 `dump`：导出已加载类字节码：**
- `dump <class-pattern> [--output=<dir>] [--limit=<n>]`
实现建议：
- 复用 `JadCommand` 的 class bytecode 获取逻辑（ClassLoader.getResourceAsStream）
- 仅允许写入可写目录（用 `SecurityValidator.canWriteFile` + 禁止系统敏感目录）
- 限制导出数量与单文件大小（避免磁盘打爆）

**6.3 `jad` 输出过滤（grep-lite）：**
- 在现有 `jad` 基础上增加：
  - `--contains=<keyword>`（包含关键字的行）
  - `--max-lines=<n>`（输出行数上限）
说明：
- 仅做文本过滤，不做复杂正则（避免 ReDoS 与输入校验冲突）

**6.4 `getstatic`（ognl-lite，只读静态字段摘要）：**
- `getstatic <class-name> <field-pattern> [--deep=<n>] [--limit=<n>]`
- 只允许读取 `static` 字段（含 `static final` 常量），不允许 invoke 方法
- 输出统一走安全 formatter（脱敏 + 限额 + 深度限制）
- 权限建议：至少 operator

**6.5 `monitor`（Arthas monitor 简化版）：**
目标：提供持续观测的“方法统计”，补齐 Arthas 高频命令之一，并与 jobs 兼容。

简化命令形态（建议）：
- `monitor <class-pattern> <method-pattern> [-i <sec>] [-n <ticks>] [--bg] [--condition ...]`
- 输出：每个 interval 输出一次统计表，ticks 到达或 stop/reset 后结束。

实现建议：
- 新增 `MonitorEnhancer`（ASM 注入）：
  - 在目标方法 entry/exit/exception 注入 `MonitorInterceptor` 调用
  - 仅记录必要指标：count、errorCount、totalCost、maxCost
- 新增 `MonitorInterceptor`（agent 内存统计）：
  - 按 monitorId/sessionId 维护 methodKey -> stats
  - 提供“取快照并清零/或滑动窗口”的 API 供命令周期输出
- 性能保护：
  - 默认只统计 depth=0（目标方法自身），不追踪子调用
  - 对高频方法建议配合 `-i` 与采样率/上限（可后续扩展）

**6.6 `vmoption`（HotSpotDiagnosticMXBean list/get/set）：**
目标：让 `vmoption` 更贴近 Arthas（不是仅展示 input arguments）。

简化命令形态（建议）：
- `vmoption list [pattern] [limit]`
- `vmoption get <name>`
- `vmoption set <name> <value>`

实现建议：
- 使用 `com.sun.management.HotSpotDiagnosticMXBean`：
  - `getDiagnosticOptions()` 列出 VMOption（可写、当前值、来源）
  - `getVMOption(name)` 查询单项
  - `setVMOption(name, value)` 修改（仅对 `isWriteable=true`）
- 权限与审计：
  - list/get：operator
  - set：admin + dangerous + 审计
  - 失败原因需明确输出（不可写/不存在/非法值）

**6.7 `thread` Top（CPU 增量排序）：**
目标：实现 Arthas `thread -n/-i` 的高频能力。

简化命令形态（建议）：
- `thread -n <N> -i <ms>`：采样间隔后按 CPU delta 排序输出

实现建议：
- 使用 `ThreadMXBean.getThreadCpuTime(threadId)`：
  - 采样前取一次快照
  - sleep interval
  - 再取一次快照，计算 delta
- 输出字段：threadId、state、name、cpuDeltaMs、cpuTotalMs（可选）
- 兼容性：若 CPU time 不支持或未启用，输出提示并降级为现有列表

**6.8 修复并增强 `mc`（产出可靠）：**
目标：确保 `mc` 真正能拿到编译产物（含 package/FQCN），并可用于 `redefine`。

实现建议：
- 修复 `MemoryJavaCompiler`：
  - `openOutputStream()` 返回自定义 OutputStream，在 close 时把 bytes 写入 compiledClasses map
  - 确保返回的 compiledClasses 包含主类与内部类
- 增强 `MemoryCompilerCommand`：
  - 在未指定 `-c` 时，解析 `package` + `class` 推导 FQCN
  - 输出目录写入路径使用 FQCN 生成子目录结构
  - 增加 `--help` 文档说明“需要 JDK”

## 架构设计
（无新增跨进程组件，仅在 agent 内新增 job/tt 子系统；保持“本机 + 单 agent 端口”模型。）

## Architecture Decision ADR
### ADR-001: 不引入 OGNL/脚本表达式
**Context:** Arthas watch/trace 强依赖 OGNL，但引入脚本会带来注入风险、JDK 兼容性问题（尤其 Java 8~17 差异），并扩大攻击面。  
**Decision:** 采用“内置字段 + 简单比较”的 `--expr/--condition` 语法。  
**Rationale:** 满足 80% 线上排障需求，成本可控，安全边界清晰。  
**Alternatives:** 引入 OGNL/JS 引擎 → 拒绝原因：安全与兼容性成本过高。  
**Impact:** 表达式能力受限，但可逐步扩展白名单字段与操作符。

## Security and Performance
- **Security:**
  - 仅本机绑定（维持默认 `127.0.0.1`）
  - `reset/stop/tt/jobs stop/dump/getstatic/logger set/vmoption set` 等高风险命令限制权限并审计
  - 输出统一走安全 formatter（限额 + 脱敏）
- **Performance:**
  - 条件评估与格式化尽量在消费侧执行
  - 默认启用计数/超时上限；队列满采用配置的 drop/evict 策略
  - job 输出 buffer 限长（避免内存膨胀）

## Testing and Deployment
- **Testing:**
  - 为表达式/条件解析器与 formatter 增加单元测试（JUnit4）
  - 为 job ring buffer 与 stop 逻辑增加并发场景测试（简化）
  - 为 trace 聚合器、monitor 统计、vmoption get/list、mc 编译产出增加最小单测
  - 手工验证：用 `com.javasleuth.test.EnhancedTestApplication` 作为目标进程，验证 watch/trace/tt/monitor/jobs/reset
  - 手工验证：验证 thread top、vmoption set（可写项）、dump/getstatic/logger set 的权限与审计
- **Deployment:**
  - 仍通过 `mvn clean package` 构建 fat-jar
  - 通过 `sleuth.sh` / `sleuth.bat` 启动并 attach
