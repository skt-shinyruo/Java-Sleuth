# 变更提案：Arthas 核心能力（简化增强版）

## 需求背景
当前 Java-Sleuth 已具备 Arthas 的部分基础能力：动态 Attach 注入 Agent、命令交互、JVM/线程/内存/类加载/JMX 信息查看、`sc/sm/jad`、以及基于 ASM 的 `watch/trace` 增强与 `redefine/retransform`。

与 Arthas 的“核心排障闭环”相比，仍存在关键缺口：
1. `watch/trace` 缺少“表达式选择 + 条件过滤”的高频能力（目前输出固定，且对象可读性有限）。
2. 缺少 `tt`（Time Tunnel）式的调用录制与复盘能力（至少需要 record/list/detail）。
3. 缺少 `jobs`（后台任务管理）能力，导致长时间观测命令阻塞当前会话、无法统一管理。
4. 缺少一键回滚增强的 `reset` 能力，排障过程中难以快速清场。

本次目标是在保持安全与实现成本可控的前提下，实现“类似 Arthas 的核心能力”，并在此基础上补齐若干高频能力，但保持**简化实现**：
- 仅支持本机诊断（默认 `127.0.0.1`，不引入远程访问模型）。
- 不引入 OGNL/脚本执行（避免表达式注入与 JDK 兼容性问题）。
- 通过“内置字段 + 简单比较”实现条件/表达式能力，配合输出限额与脱敏策略。

## 变更内容
1. 增加 `reset`：一键撤销所有增强（停止观测会话、清空 enhancer，并对涉及类执行 retransformation 以恢复原字节码）。
2. 增强 `watch/trace`：
   - 新增 `--expr`（输出字段选择）与 `--condition`（条件过滤，受控语法）
   - 增强 `trace` 输出聚合：按“单次调用”成树输出，并统计 `cost`（至少 root cost，必要时支持子调用 cost 的受限统计）
   - 提升对象输出可读性（受限展开 + 脱敏）
3. 增加 `tt-lite`：简化版 Time Tunnel（record/list/detail；不做 replay）。
4. 增加 `jobs`：后台任务管理（start/list/tail/stop），支持将 `watch/trace/tt` 以 job 方式执行并轮询获取输出。
5. 增加“高频诊断与管理”命令（对齐 Arthas 常用习惯，但保持简化）：
   - `stop`：停止 agent（关闭命令服务端并清理 transformer）
   - `session` / `perm`：查看当前会话与权限摘要
   - `version`：显示版本/构建信息
   - `options`：以 Arthas 风格查看关键运行配置（作为 `config` 的轻量别名/视图）
6. 增加“诊断增强”命令：
   - `logger`（先支持 `java.util.logging`）：列出/设置 logger level
   - `dump`：导出已加载类的 `.class` 字节码到指定目录（用于离线分析）
   - `jad` 增强：增加轻量 grep/contains 过滤输出（提升可用性）
7. 增加 `getstatic`（ognl-lite，只读）：查看类的静态字段摘要（支持字段筛选/限额/脱敏）
8. 增强 `monitor`（Arthas monitor 简化版）：对目标方法做低侵入统计（次数/QPS/RT/错误数）并周期输出（支持 jobs 后台）
9. 增强 `vmoption`（更贴近 Arthas）：支持 HotSpot VM option 的 list/get/set（仅可写项；权限限制；审计）
10. 增强 `thread`：增加“按 CPU 增量排序的 top N 线程”能力（类似 Arthas thread -n/-i）
11. 修复与增强 `mc`：确保 in-memory 编译可产出 `.class` 字节并可正确保存到磁盘（支持 package/FQCN），与 `redefine` 工作流可用

## 影响范围
- **Modules:**
  - `command`（新增/扩展命令、解析选项、job 管理入口）
  - `enhancement`（增强回滚与增强状态枚举）
  - `monitor` / `data`（tt-lite 记录数据结构、输出字段抽取）
  - `util`（表达式/条件解析与安全格式化）
  - `security`（权限分级、敏感信息输出控制策略）
  - `compiler`（mc 编译产物修复与工作流增强）
- **Files:** 预计新增与修改 10~20 个 Java 文件（以 `src/main/java/com/javasleuth/**` 为主）
- **APIs:** 无对外 HTTP API；仅 CLI 命令接口扩展
- **Data:** 不引入持久化；tt/jobs 记录使用内存 ring buffer（可配置上限）

## 核心场景

### Requirement: Reset
**Module:** enhancement / command
提供一键回滚增强能力，确保排障可控、可恢复。

#### Scenario: One-shot rollback all enhancers
在任意时刻执行 `reset`：
- 预期结果
  - 停止所有 watch/trace/tt 会话
  - 清空所有已注册 enhancer
  - 对已增强过的类执行一次 retransformation，恢复原实现
  - 输出清理统计信息（撤销的增强数、影响类数）

### Requirement: Watch/Trace Expression & Condition
**Module:** command / util / data
为 `watch/trace` 提供表达式选择与条件过滤，使其具备 Arthas 的常用排障能力，但保持简化语法与安全边界。

#### Scenario: Projection & predicate filtering
执行 `watch` 或 `trace` 时：
- 预期结果
  - 支持 `--expr` 指定输出字段（如 `params[0],return,cost,thread`）
  - 支持 `--condition` 指定过滤条件（如 `cost:gt:100`），可多次指定（AND）
  - 对对象输出做安全可读化（限制长度/数量/深度；避免巨大对象导致卡顿）

### Requirement: TT-lite (Record/List/Detail)
**Module:** command / monitor / data
提供简化版 `tt`：以低侵入方式录制调用事件，供事后查看详情。

#### Scenario: Record invocations and query
执行 `tt` 开始录制后：
- 预期结果
  - `tt list` 能看到最近 N 条记录（时间/耗时/线程/类方法）
  - `tt detail <id>` 能看到单条的入参/返回/异常摘要与耗时
  - 输出同样遵循安全格式化与限额策略

### Requirement: Jobs (Background Commands)
**Module:** command
支持将长时间观测命令后台化，并通过 job 命令统一管理生命周期与输出。

#### Scenario: Start/tail/stop background watch
以后台方式启动 `watch/trace/tt` 后：
- 预期结果
  - `jobs` 能列出运行中的任务及其状态/启动时间/命令摘要
  - `jobs tail <id> [n]` 能查看最近输出
  - `jobs stop <id>` 能停止任务并触发必要的回滚清理

### Requirement: 管理与诊断命令补齐（stop/session/perm/version/options/logger/dump/getstatic）
**Module:** command / security / util
补齐 Arthas 常用的管理与诊断命令，使排障过程更完整、更可控。

#### Scenario: 快速查看会话与权限
在任意连接中执行：
- `session` / `perm`
- 预期结果
  - 输出当前会话角色（viewer/operator/admin）、鉴权/授权模式概览
  - 输出可执行命令列表摘要与危险命令标识

#### Scenario: 停止 agent
执行 `stop`：
- 预期结果
  - agent 端命令服务优雅停止（关闭 socket、停止线程池、清理 enhancer）
  - 当前连接会断开或收到结束提示
  - 审计记录包含 stop 行为

#### Scenario: 导出类字节码
执行 `dump com.example.Foo --output=./dump`：
- 预期结果
  - 在输出目录生成对应 `.class` 文件（支持 inner class）
  - 输出写入路径与成功/失败统计
  - 路径校验通过（禁止目录穿越与系统敏感目录）

#### Scenario: 动态调整日志级别（简化版）
执行 `logger set <name> <level>`：
- 预期结果
  - 仅支持 `java.util.logging`
  - 设置后可通过 `logger get <name>` 验证生效
  - 操作被审计记录

#### Scenario: 读取静态字段摘要（ognl-lite，只读）
执行 `getstatic com.example.Config *`：
- 预期结果
  - 输出静态字段名称与格式化后的摘要值
  - 对敏感字段名自动脱敏（如 password/token/secret）
  - 输出遵循限额策略（防止大对象/大集合刷屏）

### Requirement: Monitor（Arthas monitor 简化版）
**Module:** command / enhancement / monitor / data
实现可持续观测的“方法统计监控”，补齐 Arthas 高频命令之一。

#### Scenario: 周期输出方法统计
执行 `monitor <class-pattern> <method-pattern> -i 5 -n 10`：
- 预期结果
  - 周期输出表格（每 5s 一次），包含 count/QPS/avg/max/error
  - 输出支持 `--condition`（例如 `cost:gt:100`）与 `--expr` 的子集（至少支持 cost/throw）
  - 可通过 `--bg` 进入 jobs 后台运行并可 tail/stop

### Requirement: VmOption（HotSpot 选项 list/get/set）
**Module:** command / security
提供更像 Arthas 的 `vmoption` 能力，便于线上快速查看/调整可写 VM 选项。

#### Scenario: 读取与设置可写 VM 选项
执行：
- `vmoption list [pattern] [limit]`
- `vmoption get <name>`
- `vmoption set <name> <value>`
- 预期结果
  - list/get 对 operator 可用；set 仅 admin 可用并标记危险操作
  - set 仅允许 `isWriteable=true` 的选项（并记录审计）
  - 输出包含当前值、来源、是否可写

### Requirement: Thread Top（CPU 增量排序）
**Module:** command
补齐 Arthas 常用的“找热点线程”能力。

#### Scenario: Top N 线程 CPU 增量
执行 `thread -n 5 -i 200`：
- 预期结果
  - 先采样 200ms 的线程 CPU time 增量，再按增量排序输出 top 5
  - 输出包含 threadId/name/state/cpuDelta/cpuTotal（可选）

### Requirement: 修复并增强 mc（编译产物可靠可用）
**Module:** compiler / command
确保 `mc` 输出可用于 `redefine`，避免“看似编译成功但产物为空/路径不正确”的问题。

#### Scenario: 编译并保存 class 文件
执行 `mc src/main/java/.../Foo.java -o ./out -c com.example.Foo`：
- 预期结果
  - 能生成 `.class` 字节并写入 `./out/com/example/Foo.class`
  - 若源码包含 `package`，在未指定 `-c` 时也能推导出 FQCN
  - 输出列出所有生成类（含内部类）与写入路径

## 风险评估
- **Risk:** 观测输出可能包含敏感信息（参数/返回值/异常信息），存在数据泄露风险  
  - **Mitigation:** 输出字段默认最小化；提供输出限额、长度截断、浅层展开、敏感 key 脱敏；高权限命令（reset/tt/jobs stop）限制为 admin/operator 并写入审计
- **Risk:** 插桩与输出对性能有额外开销（尤其是高频方法）  
  - **Mitigation:** 强制上限（事件条数/超时/采样），队列满策略沿用配置；条件与格式化尽量在“命令消费侧”执行，减少被观测线程工作量
- **Risk:** 回滚不彻底导致被观测类保持增强状态  
  - **Mitigation:** `reset` 记录并 retransform 已触达类；失败时输出告警并建议重启/手动 retransform
- **Risk:** `getstatic`/`dump` 可能泄露敏感配置或字节码信息  
  - **Mitigation:** 权限提升（至少 operator）；输出强脱敏；`dump` 仅写入安全路径并限制导出数量；所有操作审计记录
- **Risk:** `vmoption set` 可能改变 JVM 行为导致不可预期影响  
  - **Mitigation:** 仅 admin；仅可写选项；标记 dangerous 并审计；可选增加“二次确认”开关（后续增强）
- **Risk:** `monitor` 长时间插桩与统计可能带来性能开销  
  - **Mitigation:** 默认严格限额（采样/输出间隔/上限）；支持 jobs stop；reset 一键回滚
