# Changelog

This file records all important project changes.
Format based on [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/),
version numbers follow [Semantic Versioning](https://semver.org/lang/zh-CN/).

## [Unreleased]

### Added
- 插件化命令注册与分帧协议基础设施
- auth 命令与会话角色绑定
- HELLO/CONFIG 握手协商与 binary 严格二进制帧协议
- security.mode=off|hmac（默认 off）与 HMAC+nonce 请求签名/基础防重放
- server.bind.address 默认 127.0.0.1（降低默认口令/明文传输暴露面）
- 插件命令动态权限注册（避免 unknown command 被 AuthorizationManager 拒绝）
- 协议/插件/安全相关指标：handshake、binary upgrade、plugin load、security_verify
- watch/trace 事件丢弃/采样计数与 status 输出
- UTF-8 行编解码（Utf8LineCodec）与 `protocol.text.max.line.bytes` 单行字节数上限
- 轻量日志封装 `SleuthLogger`（通过 `logging.level` 控制增强日志等级）
- 安全默认策略开关：`security.anonymous.viewer`（默认 false）
- Arthas-like 核心能力（简化版）：watch/trace/monitor/tt/jobs/reset 及管理命令集
- `jobs`（JobManager + list/tail/stop）与 `--bg` 后台执行（watch/trace/monitor/tt）
- `tt`（TT-lite）：record/list/detail（不含 replay）
- watch/trace 增强：`--expr`（字段选择）+ `--condition`（受控条件过滤，支持 cost 单位）
- trace 聚合输出：按“单次调用”成树输出（TraceAggregator）
- `monitor` 重写为周期统计输出（可后台 jobs）
- `dump`（导出 class 资源字节码）、`getstatic`（静态字段只读查看）、`logger`（JUL list/set）
- `session`/`perm`/`version`/`stop`/`reset` 等管理命令
- `vmoption`（HotSpotDiagnosticMXBean）支持 list/get/set（set 需 admin）
- `thread -n <N> -i <ms>`：按 CPU delta 输出 top 线程
- 单测补齐：wildcard/ring buffer/condition/trace 聚合/内存编译产物
- `stack` 方法触发调用栈追踪（Arthas stack 简化版，支持 `--bg/--depth`），并在 status 输出 stack 事件统计
- `tt replay <recordId>`：生成复现代码模板（lite，不在目标 JVM 执行）
- 插桩健壮性回归：Watch/Tt 增强器的返回值/异常语义与字节码校验（VerifyError）测试
- `JarLocator`：启动/发布不再依赖硬编码 jar 名称，支持 `-Dsleuth.agent.jar` / `SLEUTH_AGENT_JAR` 覆盖
- 安全自举配置：`security.bootstrap.hmac.*`、`security.hmac.session.role`（attach 默认启用 hmac 并下发 secret）
- 口令认证开关与配置：`security.auth.password.enabled` + `security.auth.*.password`（支持 `SLEUTH_AUTH_*_PASSWORD`）
- 插件加固配置：`plugins.enabled`（默认关闭）+ `plugins.allowlist.sha256`
- trace 调用级采样覆盖：`trace --sample <rate>`（支持按 trace 会话覆盖采样率）
- 审计输出可控：`logging.audit.console.enabled`、`logging.audit.file.path`、`logging.security.file.path`（默认落盘到 tmp 并带 pid 后缀）
- monitor 独立采样 key：`monitoring.monitor.sample.rate`
- 关键边界单测补齐：非回环 bind + `security.mode=off` 拒绝启动、`security.mode=hmac` 但 secret 为空拒绝启动、协议上限异常路径

### Changed
- CommandProcessor 改为注册表 + 统一执行管线
- Launcher 支持 framed/stream 协议与端口配置读取
- Enhancer 支持链式叠加与按会话移除
- CommandProcessor 支持 bind address + handshake 协商并可升级 binary 通道
- Launcher 支持 handshake 协商与 binary 通道；在 security.mode=hmac 时自动封装 SIG 请求
- 项目根目录结构整理：文档集中到 docs/，脚本归档到 scripts/
- 安全默认：关闭匿名 viewer（`security.anonymous.viewer=false`），连接后需要先执行 `auth`
- sysprop 写入改为显式子命令 `sysprop set <key> <value>`（并要求更高权限）
- 插桩过滤策略放开常见代理类（例如 Spring/CGLIB `$$EnhancerBySpringCGLIB$$`），transform 逐次日志默认降噪（DEBUG 才输出）
- 性能维护策略：默认不再定时触发 `System.gc()`（由 `performance.maintenance.force_gc` 控制）
- Config/Sysprop 等命令输出对敏感值自动脱敏（避免控制台/日志泄露 secret）
- Watch/Trace/Monitor/TT 方法匹配支持 `*` 通配符（例如 `execute*`），并修复异常路径退出事件捕获
- WatchResult 输出改为使用安全格式化（SleuthValueFormatter：限深/限长/脱敏）
- Monitor/VmOption 命令接口调整为更贴近 Arthas 的简化子命令模型
- 默认 trace 采样率调整为更保守值（`monitoring.trace.sample.rate=0.1`），降低高 QPS 场景误用风险
- Launcher/脚本启动方式去版本/目录硬编码：支持任意 cwd 启动与通配符定位 `*-jar-with-dependencies.jar`
- 审计日志默认不再刷屏控制台（需显式开启 `logging.audit.console.enabled=true`）
- fat-jar Manifest 补齐 `Main-Class`，支持 `java -jar` 直接启动 Launcher（不破坏 Agent 能力）
- 默认配置与实现对齐：移除无效 `production.*`，补齐 `jobs.*`/`protocol.frame.max.payload` 等关键默认项并同步文档

### Fixed
- watch/trace 队列增加背压与采样
- CommandParser 反斜杠转义字符解析修复
- PerformanceOptimizer/MemoryOptimizer 编译问题修复（静态 API/缓存清理/ MBean 接口）
- Launcher 进程列表过滤后序号不一致导致的误选问题
- Launcher 连接地址不再写死 localhost（按 bind 地址/协商信息解析，0.0.0.0/:: 回退 loopback）
- 传输层消除 BufferedReader/PrintWriter 与 Data*Stream 混用导致的升级不稳定风险
- AuthenticationManager 锁定窗口与客户端标识解析修复（支持 /ip:port、IPv6、unknown）
- 审计日志脱敏加强：auth/config/sysprop 等命令参数与 sessionId 不再以明文写入
- server.max.connections 与 performance.command.timeout 配置落地生效
- MemoryJavaCompiler 编译产物未落入 compiledClasses 的问题（通过 OutputStream.close 挂钩收集字节码）
- HMAC 模式下新连接会话自举：避免“匿名 viewer 关闭 + 口令认证关闭”导致命令不可用
- TraceInterceptor 采样改为调用级一致：entry/exit/subcall 配对与 depth 稳定
- 插件目录加载默认关闭，并在 shutdown 释放 URLClassLoader（降低 Windows JAR 锁定风险）
- AuthenticationManager 移除硬编码 demo 口令，改为显式配置/环境变量
- AuditLogger 支持可配置落盘路径并减少对目标 JVM stdout/stderr 的污染
- Java 8 运行时兼容性：移除 Java 11 `String.repeat` 与 Java 9 `Field.canAccess` 依赖，并在 `mvn verify` 阶段增加 Java 8 API 校验（避免“JDK 11 编译通过、Java 8 运行炸”）
- jad 反编译可用性：将获取到的 `.class` bytecode 可靠传入 CFR（临时文件方式），避免“传了类名但无输入”导致的空输出
- session 泄露/串线：`session` 命令默认不缓存、输出 token 默认脱敏（`--show-token` 显式开启）；命令缓存 key 增加 clientId 维度防止跨客户端串线
- wildcard/regex 稳定性：通配符匹配改为转义正则元字符；`sm -E` 采用 RE2/J 避免 ReDoS 并提供更友好的语法错误提示
- watch/tt 资源风险：采集阶段引入“值快照”（限深/限长），避免把参数/返回值/异常对象强引用驻留到队列/环形缓冲导致内存压力
- trace 语义：同一类内“可被 trace 的方法调用”不再产生重复 SUB_METHOD_CALL；采样以根调用为单位并向子调用继承，降低碎片化树
- stdout/stderr 污染：`logging.performance.enabled` 默认关闭（可配置开启）
- `tt replay` 模板输出移除 TODO 占位，改为明确限制说明与更可复制的 Java 模板
- `profiler` 文案澄清当前实现不依赖 async-profiler（避免误导）

## [1.0.0] - 2026-01-28

### Added
- 初始化知识库文档结构与项目概览
