# Java-Sleuth Command Reference

Java-Sleuth 提供多种诊断与监控命令，且会随版本演进。完整且权威的命令列表请以运行时 `help` 输出为准；本文档侧重常用命令与关键选项。

## Core System Commands

### `help`
Lists all available commands with descriptions.
- **Usage**: `help`
- **Description**: Display all available commands and their descriptions

### `quit`
Exits the Java-Sleuth session.
- **Usage**: `quit`
- **Description**: Exit the current session

## 认证与配置命令

> 说明：默认配置关闭匿名 viewer（`security.anonymous.viewer=false`），同时默认关闭口令认证（`security.auth.password.enabled=false`）。
> 推荐使用 Launcher 的 HMAC 自举（`security.bootstrap.hmac.on.attach=true`）获得安全且可用的默认会话；或显式开启口令认证并配置密码。

### `auth`
对当前连接进行认证，并将会话角色升级为对应用户角色。
- **Usage**: `auth <username> <password>`
- **Description**: Authenticate current session and upgrade role
- **Notes**:
  - 认证成功后不会回显 sessionId（避免泄露 bearer token）
  - 口令认证默认关闭：需设置 `security.auth.password.enabled=true`，并通过配置项或环境变量设置密码：
    - 配置项：`security.auth.admin.password` / `security.auth.operator.password` / `security.auth.viewer.password`
    - 环境变量：`SLEUTH_AUTH_ADMIN_PASSWORD` / `SLEUTH_AUTH_OPERATOR_PASSWORD` / `SLEUTH_AUTH_VIEWER_PASSWORD`

### `config`
管理运行时配置（优先级高于默认配置与外部文件），并对敏感值进行脱敏输出。
- **Usage**:
  - `config` / `config status` - Show configuration status
  - `config get <key>` - Get configuration value（敏感 key 自动脱敏）
  - `config set <key> <value>` - Set runtime override（敏感 key 自动脱敏）
  - `config remove <key>` - Remove runtime override
  - `config clear` - Clear all runtime overrides
  - `config show` - Show current key settings（含安全/协议关键项）
- **Notes**:
  - 在默认 RBAC 策略下，`config` 相关操作建议仅由 ADMIN 执行

## Monitoring Commands

### `dashboard`
Displays a comprehensive JVM dashboard with runtime statistics.
- **Usage**: `dashboard`
- **Description**: Real-time overview of JVM health
- **Information includes**:
  - JVM details (name, version, vendor, uptime)
  - Memory usage (heap and non-heap)
  - Thread information with deadlock detection
  - Class loading statistics
  - Garbage collection metrics

### `jvm`
Shows detailed JVM information and runtime details.
- **Usage**: `jvm [--help]`
- **Description**: Comprehensive JVM system information
- **Information includes**:
  - Virtual machine specifications
  - Operating system details with CPU and memory metrics
  - Runtime information (PID, paths, arguments)
  - Class loading and JIT compilation stats
  - Complete JVM startup arguments

### `thread`
Displays thread information and analysis.
- **Usage**: `thread [options]`
- **Description**: Thread monitoring and analysis
- **Features**:
  - Thread states and stack traces
  - Deadlock detection
  - CPU usage per thread
  - Blocked thread analysis

### `memory`
Provides detailed memory information and statistics.
- **Usage**: `memory [subcommand]`
- **Subcommands**:
  - `overview` (default): Comprehensive memory overview
  - `pools`: Detailed memory pool information
  - `gc`: Garbage collection statistics
  - `heap`: Heap memory details only
  - `nonheap`: Non-heap memory details only
  - `direct`: Direct memory information
- **Description**: Complete memory analysis including pools, GC stats, and thresholds

## System Information Commands

### `sysprop`
View and modify system properties.
- **Usage**:
  - `sysprop` - List all properties
  - `sysprop <key>` - Get specific property
  - `sysprop <pattern>` - Search with wildcards
  - `sysprop set <key> <value>` - Set property（写入需更高权限，value 暂不支持空格）
- **Description**: System property management with security validation
- **Features**:
  - Wildcard pattern matching
  - Sensitive value masking
  - Property modification with validation

### `sysenv`
Display system environment variables.
- **Usage**: `sysenv [pattern]`
- **Description**: Environment variable inspection
- **Features**:
  - Complete environment listing
  - Pattern-based filtering
  - Sensitive data protection

### `vmoption`
Display and modify JVM runtime options.
- **Usage**: `vmoption [name] [value]`
- **Description**: JVM option management
- **Features**:
  - List all VM options
  - Modify writable options
  - Option validation

## Class and Method Analysis Commands

### `sc` (Search Class)
Search for loaded classes.
- **Usage**: `sc <class-pattern>`
- **Description**: Class discovery and inspection
- **Features**:
  - Pattern-based class search
  - Class hierarchy information
  - Method and field listings

### `sm` (Search Method)
Search for methods in loaded classes.
- **Usage**: `sm <class-pattern> <method-pattern>`
- **Description**: Method discovery and analysis
- **Features**:
  - Method signature search
  - Parameter and return type info
  - Access modifier details

### `jad`
Decompile Java classes to source code.
- **Usage**: `jad <class-name>`
- **Description**: Class decompilation using CFR
- **Features**:
  - Source code reconstruction
  - Inner class support
  - Syntax highlighting
  - Multiple decompilation options

## Instrumentation Commands

### `watch`
Monitor method calls in real-time.
- **Usage**: `watch <class-pattern> <method-pattern>`
- **Description**: Real-time method monitoring
- **Features**:
  - Parameter and return value capture
  - Exception monitoring
  - Execution time tracking
  - Conditional filtering

### `trace`
Trace method call paths and timing.
- **Usage**: `trace <class-pattern> <method-pattern> [options]`
- **Description**: Method call tracing with timing analysis
- **Features**:
  - Call stack visualization
  - Performance bottleneck identification
  - Nested call tracking
- **Common options**:
  - `-d, --depth <num>`: 最大展示深度（默认 10）
  - `-n, --count <num>`: 最大捕获调用次数（默认 20）
  - `-t, --timeout <sec>`: 超时时间（默认 30s）
  - `--sample <rate>`: 覆盖采样率（0.0..1.0），默认由 `monitoring.trace.sample.rate` 控制

### `tt` (Time Tunnel - lite)
记录方法调用现场，用于后续查看与生成 replay 模板（lite：不执行回放）。
- **Usage**: `tt record <class-pattern> <method-pattern> [options]`
- **Common subcommands**:
  - `tt list [n]`
  - `tt detail <recordId>`
  - `tt replay <recordId>`（仅生成模板，不执行）

## Hot Reload Commands

> ⚠️ 提示：`mc` / `redefine` / `retransform` / `heapdump` / `reset` / `stop` 属于危险命令。
> 默认启用二次确认：首次执行会返回一次性 token，需要在短 TTL 内追加 `--confirm <token>` 重试后才会真正执行。

### `mc` (Memory Compiler)
Compile Java source code in memory.
- **Usage**: `mc <source-file-path> [options]`
- **Description**: Runtime Java compilation
- **Notes**:
  - 第一个参数是 `.java` 源文件路径（会做基础校验与过滤）
- **Features**:
  - In-memory compilation
  - Dynamic class generation
  - Compilation error reporting

### `redefine`
Hot-reload modified class files.
- **Usage**: `redefine <class-name> <class-file-path>`
- **Description**: Runtime class redefinition
- **Features**:
  - Hot code replacement
  - Method body updates
  - Development productivity enhancement

### `retransform`
Re-transform classes using current transformers.
- **Usage**: `retransform <class-pattern>`
- **Description**: Class retransformation
- **Features**:
  - Apply new transformations
  - Update instrumentation
  - Refresh monitoring

### `reset`
Reset all active enhancements/sessions and best-effort retransform back to original bytecode.
- **Usage**: `reset`
- **Notes**:
  - 危险命令：默认需要二次确认 token（`--confirm <token>`）
  - 会停止后台 jobs，并清空 watch/trace/monitor/tt/stack 等拦截器会话

### `stop`
Stop Java-Sleuth agent inside target JVM (shutdown command server and transformer).
- **Usage**: `stop`
- **Notes**:
  - 危险命令：默认需要二次确认 token（`--confirm <token>`）

## Advanced Analysis Commands

### `classloader`
Display classloader hierarchy and information.
- **Usage**: `classloader [options]`
- **Description**: Classloader analysis and troubleshooting
- **Features**:
  - Hierarchy visualization
  - Class loading delegation
  - Resource location
  - Classloader leaks detection

### `mbean`
Browse and interact with MBeans.
- **Usage**: `mbean [pattern]`
- **Description**: JMX MBean exploration
- **Features**:
  - MBean discovery
  - Attribute inspection
  - Operation invocation
  - Management interface access

### `heapdump`
Create heap dumps for memory analysis.
- **Usage**: `heapdump [options] [filename]`
- **Options**:
  - `--live`, `-l`: Live objects only (default)
  - `--all`, `-a`: All objects including unreachable
  - `--file=<name>`: Specify output filename
- **Description**: Memory dump generation
- **Features**:
  - Automatic filename generation
  - Size and timing information
  - Analysis tool recommendations
  - Security validation for file paths

## Performance Features

All commands are optimized for production use with:
- **Caching**: Expensive operations are cached for 5 seconds
- **Async Execution**: Long-running operations use background threads
- **Performance Monitoring**: Slow operations (>1s) are automatically logged
- **Resource Management**: Proper cleanup and resource pooling

## Security Features

Java-Sleuth includes comprehensive security measures:
- **Input Validation**: All user inputs are sanitized and validated
- **Path Security**: File operations validate paths and prevent directory traversal
- **Sensitive Data Masking**: Passwords, keys, and tokens are automatically masked
- **Permission Checking**: Operations respect JVM security manager policies
- **Class Access Control**: Blocks access to security-sensitive classes

## Usage Examples

```bash
# Monitor application health
dashboard

# Check memory usage details
memory pools

# Find all Spring classes
sc *Spring*

# Watch method calls
watch com.example.UserService login

# Create a heap dump
heapdump --live myapp-heap.hprof

# Search system properties
sysprop java.*

# Decompile a class
jad com.example.UserService
```

## Command Categories Summary

- Monitoring/Status: `dashboard`, `health`, `metrics`, `status`, `jvm`, `thread`, `memory`, `mbean`
- System Info: `sysprop`, `sysenv`, `vmoption`
- Class Analysis: `sc`, `sm`, `jad`, `classloader`
- Instrumentation: `watch`, `trace`, `monitor`, `stack`, `tt`, `profiler`
- Hot Reload: `mc`, `redefine`, `retransform`, `reset`
- Data/Debug: `dump`, `getstatic`, `heapdump`, `logger`
- Session/Security: `auth`, `session`, `perm`, `audit`, `config`
- Job Control: `jobs`
- Core: `help`, `quit`, `stop`
