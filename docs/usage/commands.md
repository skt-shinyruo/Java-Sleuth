# Java-Sleuth Command Reference

Java-Sleuth provides 20 comprehensive diagnostic and monitoring commands for Java applications.

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

> 说明：从 2026-01-29 起，默认配置关闭匿名 viewer（`security.anonymous.viewer=false`）。因此新连接建立后，除 `auth` 外的命令可能会提示需要认证。

### `auth`
对当前连接进行认证，并将会话角色升级为对应用户角色。
- **Usage**: `auth <username> <password>`
- **Description**: Authenticate current session and upgrade role
- **Notes**:
  - 认证成功后不会回显 sessionId（避免泄露 bearer token）
  - 默认账号口令为演示用途，具体见 `AuthenticationManager`（建议仅在受控环境使用）

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
- **Usage**: `trace <class-pattern> <method-pattern>`
- **Description**: Method call tracing with timing analysis
- **Features**:
  - Call stack visualization
  - Performance bottleneck identification
  - Nested call tracking

## Hot Reload Commands

### `mc` (Memory Compiler)
Compile Java source code in memory.
- **Usage**: `mc <source-code>`
- **Description**: Runtime Java compilation
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

1. **Monitoring** (5): `dashboard`, `jvm`, `thread`, `memory`, `mbean`
2. **System Info** (3): `sysprop`, `sysenv`, `vmoption`
3. **Class Analysis** (3): `sc`, `sm`, `jad`
4. **Instrumentation** (2): `watch`, `trace`
5. **Hot Reload** (3): `mc`, `redefine`, `retransform`
6. **Advanced** (2): `classloader`, `heapdump`
7. **Core** (2): `help`, `quit`

**Total: 20 Commands** - All production-ready with performance optimization and security hardening.
