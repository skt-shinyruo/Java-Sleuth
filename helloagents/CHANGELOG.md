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

## [1.0.0] - 2026-01-28

### Added
- 初始化知识库文档结构与项目概览
