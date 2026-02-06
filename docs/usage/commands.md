# Java-Sleuth 命令参考

Java-Sleuth 提供多种诊断与监控命令，且会随版本演进。完整且权威的命令列表请以运行时 `help` 输出为准；本文档侧重常用命令与关键选项。

## 核心系统命令

### `help`
列出所有可用命令及其说明。
- **用法**: `help`
- **说明**: 显示所有可用命令及其描述

### `quit`
退出 Java-Sleuth 会话。
- **用法**: `quit`
- **说明**: 退出当前会话

## 会话与配置命令

> 说明：当前默认不启用认证/签名校验（`security.mode=off`），并默认关闭 RBAC（`security.authorization.enabled=false`），因此多数情况下无需认证即可使用。
> 只有当你显式启用 RBAC（`security.authorization.enabled=true`）并配合 HMAC（`security.mode=hmac`）或口令认证（`security.auth.password.enabled=true`）时，`auth/perm` 等权限相关能力才会真正生效。

### `auth`
对当前连接进行认证，并将会话角色升级为对应用户角色。
- **用法**: `auth <username> <password>`
- **说明**: 对当前会话进行认证并升级角色
- **备注**:
  - 认证成功后不会回显 sessionId（避免泄露 bearer token）
  - 口令认证默认关闭：需设置 `security.auth.password.enabled=true`，并通过配置项或环境变量设置密码：
    - 配置项：`security.auth.admin.password` / `security.auth.operator.password` / `security.auth.viewer.password`
    - 环境变量：`SLEUTH_AUTH_ADMIN_PASSWORD` / `SLEUTH_AUTH_OPERATOR_PASSWORD` / `SLEUTH_AUTH_VIEWER_PASSWORD`

### `config`
管理运行时配置（优先级高于默认配置与外部文件），并对敏感值进行脱敏输出。
- **用法**:
  - `config` / `config status` - 查看配置状态
  - `config get <key>` - 获取配置值（敏感 key 自动脱敏）
  - `config set <key> <value>` - 设置运行时覆盖项（敏感 key 自动脱敏）
  - `config remove <key>` - 删除指定覆盖项
  - `config clear` - 清空所有运行时覆盖项
  - `config show` - 展示当前关键配置（含安全/协议关键项）
- **备注**:
  - 若启用 RBAC（`security.authorization.enabled=true`），`config` 相关操作建议仅由 ADMIN 执行

## 监控命令

### `dashboard`
展示 JVM 综合仪表盘，包含运行时统计信息。
- **用法**: `dashboard`
- **说明**: JVM 健康状况实时概览
- **信息包含**:
  - JVM 详情（名称、版本、供应商、运行时长）
  - 内存使用（堆与非堆）
  - 线程信息（含死锁检测）
  - 类加载统计
  - 垃圾回收指标

### `jvm`
展示 JVM 详细信息与运行时细节。
- **用法**: `jvm [--help]`
- **说明**: JVM 系统信息总览
- **信息包含**:
  - 虚拟机规格信息
  - 操作系统信息（含 CPU 与内存指标）
  - 运行时信息（PID、路径、参数）
  - 类加载与 JIT 编译统计
  - 完整的 JVM 启动参数

### `thread`
展示线程信息并提供分析能力。
- **用法**: `thread [options]`
- **说明**: 线程监控与分析
- **特性**:
  - 线程状态与调用栈
  - 死锁检测
  - 线程 CPU 使用情况
  - 阻塞线程分析

### `memory`
提供详细的内存信息与统计。
- **用法**: `memory [subcommand]`
- **子命令**:
  - `overview`（默认）：综合内存概览
  - `pools`：内存池详情
  - `gc`：垃圾回收统计
  - `heap`：仅展示堆内存
  - `nonheap`：仅展示非堆内存
  - `direct`：直接内存信息
- **说明**: 覆盖内存池、GC 统计与阈值等信息的完整内存分析

## 系统信息命令

### `sysprop`
查看与修改系统属性。
- **用法**:
  - `sysprop` - 列出所有属性
  - `sysprop <key>` - 获取指定属性
  - `sysprop <pattern>` - 使用通配符搜索
  - `sysprop set <key> <value>` - 写入属性（写入需更高权限，value 暂不支持空格）
- **说明**: 带安全校验的系统属性管理
- **特性**:
  - 通配符模式匹配
  - 敏感值脱敏
  - 写入校验与权限约束

### `sysenv`
展示系统环境变量。
- **用法**: `sysenv [pattern]`
- **说明**: 环境变量查看
- **特性**:
  - 完整环境变量列表
  - 基于模式的过滤
  - 敏感信息保护

### `vmoption`
展示与修改 JVM 运行时选项。
- **用法**: `vmoption [name] [value]`
- **说明**: JVM 选项管理
- **特性**:
  - 列出所有 VM 选项
  - 修改可写选项
  - 选项校验

## 类与方法分析命令

### `sc`（查找类）
搜索已加载类。
- **用法**: `sc <class-pattern>`
- **说明**: 类发现与检视
- **特性**:
  - 基于模式的类搜索
  - 类层级信息
  - 方法与字段列表

### `sm`（查找方法）
在已加载类中搜索方法。
- **用法**: `sm <class-pattern> <method-pattern>`
- **说明**: 方法发现与分析
- **特性**:
  - 方法签名搜索
  - 参数与返回类型信息
  - 访问修饰符信息

### `jad`
反编译 Java 类为源码。
- **用法**: `jad <class-name>`
- **说明**: 基于 CFR 的类反编译
- **特性**:
  - 源码还原
  - 内部类支持
  - 语法高亮
  - 多种反编译选项

## 插桩命令

### `watch`
实时监控方法调用。
- **用法**: `watch <class-pattern> <method-pattern>`
- **说明**: 实时方法监控
- **特性**:
  - 参数与返回值捕获
  - 异常监控
  - 执行耗时统计
  - 条件过滤
- **延伸阅读**: `docs/tutorial/command-instrumentation-and-rollback.md`（命令触发插桩与回滚链路）

### `trace`
跟踪方法调用路径与耗时。
- **用法**: `trace <class-pattern> <method-pattern> [options]`
- **说明**: 带耗时分析的方法调用链追踪
- **特性**:
  - 调用栈可视化
  - 性能瓶颈定位
  - 嵌套调用跟踪
- **延伸阅读**: `docs/tutorial/command-instrumentation-and-rollback.md`（命令触发插桩与回滚链路）
- **常用选项**:
  - `-d, --depth <num>`: 最大展示深度（默认 10）
  - `-n, --count <num>`: 最大捕获调用次数（默认 20）
  - `-t, --timeout <sec>`: 超时时间（默认 30s）
  - `--sample <rate>`: 覆盖采样率（0.0..1.0），默认由 `monitoring.trace.sample.rate` 控制

### `tt`（Time Tunnel - lite）
记录方法调用现场，用于后续查看与生成 replay 模板（lite：不执行回放）。
- **用法**: `tt record <class-pattern> <method-pattern> [options]`
- **常用子命令**:
  - `tt list [n]`
  - `tt detail <recordId>`
  - `tt replay <recordId>`（仅生成模板，不执行）

## 热重载命令

> ⚠️ 提示：`mc` / `redefine` / `retransform` / `heapdump` / `reset` / `stop` 属于危险命令。
> 当前默认不启用二次确认（`security.dangerous.confirm.enabled=false`）。如需降低误触风险，可显式开启；开启后首次执行会返回一次性 token，需要在短 TTL 内追加 `--confirm <token>` 重试后才会真正执行。

### `mc`（内存编译器）
在内存中编译 Java 源码。
- **用法**: `mc <source-file-path> [options]`
- **说明**: 运行时 Java 编译
- **备注**:
  - 第一个参数是 `.java` 源文件路径（会做基础校验与过滤）
- **特性**:
  - 内存编译
  - 动态类生成
  - 编译错误报告

### `redefine`
热替换修改后的 class 文件。
- **用法**: `redefine <class-name> <class-file-path>`
- **说明**: 运行时类重定义
- **特性**:
  - 热代码替换
  - 方法体更新
  - 提升开发调试效率

### `retransform`
使用当前 transformers 对类进行重新转换。
- **用法**: `retransform <class-pattern>`
- **说明**: 类 retransformation
- **特性**:
  - 应用新的转换逻辑
  - 更新插桩
  - 刷新监控能力

### `reset`
重置所有活动增强/会话，并尽力将字节码恢复到原始状态（best-effort retransform）。
- **用法**: `reset`
- **备注**:
  - 危险命令：可选二次确认 token（由 `security.dangerous.confirm.enabled` 控制）
  - 会停止后台 jobs，并清空 watch/trace/monitor/tt/stack 等拦截器会话
  - 教学说明：`docs/tutorial/command-instrumentation-and-rollback.md`（reset/stop 影响范围与回滚机制）

### `stop`
停止目标 JVM 内的 Java-Sleuth agent（关闭命令服务与 transformer）。
- **用法**: `stop`
- **备注**:
  - 危险命令：可选二次确认 token（由 `security.dangerous.confirm.enabled` 控制）
  - 教学说明：`docs/tutorial/command-instrumentation-and-rollback.md`（reset/stop 影响范围与回滚机制）

## 高级分析命令

### `classloader`
展示 ClassLoader 层级与相关信息。
- **用法**: `classloader [options]`
- **说明**: ClassLoader 分析与排障
- **特性**:
  - 层级可视化
  - 类加载委派链路
  - 资源定位
  - ClassLoader 泄漏检测

### `mbean`
浏览并操作 MBeans。
- **用法**: `mbean [pattern]`
- **说明**: JMX MBean 探索
- **特性**:
  - MBean 发现
  - 属性查看
  - Operation 调用
  - 管理接口访问

### `heapdump`
创建堆转储文件用于内存分析。
- **用法**: `heapdump [options] [filename]`
- **选项**:
  - `--live`, `-l`: 仅导出存活对象（默认）
  - `--all`, `-a`: 导出全部对象（包含不可达对象）
  - `--file=<name>`: 指定输出文件名
- **说明**: 内存转储生成
- **特性**:
  - 自动生成文件名
  - 输出大小与耗时信息
  - 分析工具建议
  - 文件路径安全校验

### `vmtool`（lite）
实例追踪 + 对象字段检视 + 受控方法调用（简化版）。

> 重要限制：`vmtool track` 基于构造器插桩，只能追踪“启用 track 后新创建”的对象；不等价于遍历全堆存活实例。

- **用法**：
  - `vmtool tracks` - 查看当前所有 track 会话
  - `vmtool track <class-pattern> [--loader <id>] [--first] [--subclasses] [--max <n>] [--class-limit <n>]`
  - `vmtool instances <track-id> [--limit <n>] [--alive] [--where lhs:op:rhs]`
  - `vmtool inspect <track-id> <ref-id> [--deep <n>] [--fields <n>] [--static]`
  - `vmtool invoke <track-id> <ref-id> <method> [args...] [--declared] [--unsafe] [--deep <n>] [--confirm <token>]`
  - `vmtool invoke-static <class-pattern> <method> [args...] [--loader <id>] [--first] [--declared] [--unsafe] [--deep <n>] [--confirm <token>]`
  - `vmtool stop <track-id>`
  - `vmtool histogram <class-pattern> [--top <n>] [--all]`
- **筛选条件（--where）**：
  - `class/id/thread/ageMs`（元数据）
  - `field.xxx`（读取实例字段，敏感字段名自动脱敏）
  - `op`：`eq/ne/gt/gte/lt/lte/contains/startswith/endswith`

## 性能特性

所有命令都针对生产使用场景做了优化：
- **缓存**：高开销操作会缓存 5 秒
- **异步执行**：长耗时操作使用后台线程
- **性能监控**：慢操作（> 1s）会自动记录日志
- **资源管理**：及时清理与资源池化复用

## 安全特性

Java-Sleuth 内置较完善的安全措施：
- **输入校验**：对用户输入进行清洗与校验
- **路径安全**：文件操作校验路径并防止目录穿越
- **敏感信息脱敏**：密码、key、token 等会自动脱敏
- **权限检查**：相关操作遵循 JVM 安全策略约束
- **类访问控制**：阻止访问安全敏感类

## 使用示例

```bash
# 监控应用健康
dashboard

# 查看内存池详情
memory pools

# 查找所有 Spring 相关类
sc *Spring*

# 监控方法调用
watch com.example.UserService login

# 生成堆转储文件
heapdump --live myapp-heap.hprof

# 搜索系统属性
sysprop java.*

# 反编译某个类
jad com.example.UserService
```

## 命令分类速览

- 监控/状态：`dashboard`, `health`, `metrics`, `status`, `jvm`, `thread`, `memory`, `mbean`
- 系统信息：`sysprop`, `sysenv`, `vmoption`
- 类分析：`sc`, `sm`, `jad`, `classloader`
- 插桩：`watch`, `trace`, `monitor`, `stack`, `tt`, `profiler`
- 热重载：`mc`, `redefine`, `retransform`, `reset`
- 数据/调试：`dump`, `getstatic`, `heapdump`, `logger`
- 对象实例：`vmtool`
- 会话/安全：`auth`, `session`, `perm`, `audit`, `config`
- 任务控制：`jobs`
- 核心：`help`, `quit`, `stop`
