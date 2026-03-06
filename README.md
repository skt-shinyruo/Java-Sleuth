# Java-Sleuth

A lightweight Java diagnostic and monitoring tool inspired by Arthas. Java-Sleuth provides real-time insights into running Java applications without requiring application restart.

## Features

### Phase 1 - Basic Infrastructure and JVM Information ✅
- ✅ **JVM Dashboard**: Real-time JVM statistics (memory, threads, GC, etc.)
- ✅ **Thread Monitoring**: Thread analysis and deadlock detection
- ✅ **Class Search**: Search for loaded classes by pattern
- ✅ **Method Search**: Search for methods in loaded classes
- ✅ **JVM Attachment**: Dynamic attachment to running Java processes

### Phase 2 - Method Execution Monitoring ✅
- ✅ **Method Watch**: Monitor method execution with parameters, return values, and timing
- ✅ **Method Tracing**: Trace method call chains with execution flow and timing
- ✅ **Bytecode Enhancement**: ASM-based method instrumentation framework
- ✅ **Real-time Monitoring**: Live capture of method execution data

### Phase 3 - Hot Code Reload ✅
- ✅ **Class Redefinition**: Hot-swap class implementations without JVM restart
- ✅ **Memory Compiler**: Compile Java source code in memory
- ✅ **Class Retransformation**: Apply bytecode transformations to loaded classes
- ✅ **Dynamic Code Updates**: Modify running applications on-the-fly

### Phase 4 - Production Diagnostics and System Introspection ✅
- ✅ **Health/Status/Metrics**: Quick agent health checks and runtime metrics (`health/status/metrics`)
- ✅ **System Introspection**: Inspect JVM/system properties/env and VM options (`jvm/sysprop/sysenv/vmoption`)
- ✅ **Advanced Memory Tools**: Memory analysis and heap dump (`memory/heapdump`)

### Phase 5 - Class and JMX Analysis ✅
- ✅ **Decompile Loaded Classes**: Decompile classes with CFR (`jad`)
- ✅ **ClassLoader Analysis**: Inspect loader trees and class origins (`classloader`)
- ✅ **JMX MBean Explorer**: List/get/set/invoke MBeans (`mbean`)

## Requirements

- **Java 8+** (JDK required for tools.jar on Java < 9)
- **Maven 3.6+** for building
- **Linux/macOS/Windows** support
- 构建期兼容性校验：`mvn verify` 会校验 Java 8 API 兼容性，避免在 JDK 11+ 编译但在 Java 8 运行时才炸（Attach API 属于 JDK 能力，已做合理忽略）

## Documentation

- `docs/index.md` - Documentation index
- `docs/usage/index.md` - Usage docs (getting started / commands / troubleshooting)
- `docs/ops/index.md` - Ops docs (deployment guide / runbook)

## Quick Start

### 1. Build the Project

```bash
git clone <repository-url>
cd Java-Sleuth
mvn clean package
```

### 2. Run Java-Sleuth

```bash
# Linux/macOS
./sleuth.sh

# Windows
sleuth.bat

# Or run the fat-jar directly (Launcher entrypoint)
java -jar launcher/target/java-sleuth-launcher-*-jar-with-dependencies.jar
```

### 3. Select Target JVM

Java-Sleuth will list all running Java processes. Select the one you want to monitor:

```
Available Java processes:
===============================================
[1] PID: 12345    com.example.MyApplication
[2] PID: 12346    org.springframework.boot.loader.JarLauncher
[3] PID: 12347    org.apache.catalina.startup.Bootstrap

Select a process (1-3) or 'q' to quit: 1
```

### 4. Use Interactive Commands

Once connected, you can use various commands:

```
sleuth> help
=== Java-Sleuth Help ===
Available commands:

dashboard    - Display JVM dashboard with runtime statistics
thread       - Display thread information and detect deadlocks
sc           - Search for loaded classes by pattern
sm           - Search for methods in loaded classes
watch        - Watch method execution with parameters, return values, and timing
trace        - Trace method execution call chains with timing
redefine     - Redefine loaded classes with new implementations (hot code reload)
mc           - Compile Java source code in memory and optionally save to disk
retransform  - Retransform loaded classes to apply active transformers
help         - Show help information for all commands
quit         - Exit the Java-Sleuth session
```

> 重要提示：命令服务端为 **loopback-only**（仅允许 `127.0.0.1` / `localhost` / `::1`），配置为非回环地址会拒绝启动。
> 不要通过端口转发/代理将该端口暴露到不受信任网络。
> 默认关闭 RBAC（`security.authorization.enabled=false`）；多用户主机建议启用 `security.authorization.enabled=true` + `security.auth.password.enabled=true` 并设置 `security.auth.*.password`（或环境变量 `SLEUTH_AUTH_*_PASSWORD`）。

## 安全与协议说明（2026-03-05 更新）

为降低“非回环绑定 + 明文控制”带来的风险，本项目对默认安全边界与传输层做了收敛与重构：

- 默认仅允许本机访问：`server.bind.address=127.0.0.1`（或 `localhost` / `::1`）
- 强制安全边界：配置为非回环地址（例如 `0.0.0.0` 或局域网 IP）会拒绝启动（fail-fast）
- HMAC 模式已移除：`security.mode`、`security.hmac.*`、`security.bootstrap.hmac.*` 为不支持 key（加载配置时会 fail-fast）
- 多用户主机建议启用：
  - RBAC：`security.authorization.enabled=true`
  - 口令认证：`security.auth.password.enabled=true` + `security.auth.*.password`（或环境变量 `SLEUTH_AUTH_*_PASSWORD`）
- 危险命令二次确认：默认关闭（`security.dangerous.confirm.enabled=false`），需要时可显式开启
- 高影响（impact=HIGH）治理：默认关闭二次确认（`security.impact.high.confirm.enabled=false`），需要时可显式开启并配合并发限制

传输层与资源治理相关配置：

- 传输协议：仅支持 `binary`
- `protocol.text.max.line.bytes`：文本握手/控制面单行最大字节数，避免超长输入导致资源耗尽
- `server.max.connections`：并发连接上限（超限新连接会被拒绝）
- `server.executor.queue.capacity`：连接处理线程池排队上限（用于背压与内存上限控制，过载会拒绝新连接）
- `performance.command.executor.queue.capacity`：命令执行线程池排队上限（过载会返回明确错误，避免无限排队/线程膨胀）
- `performance.command.timeout`：命令执行超时（避免长耗时命令永久占用线程）
- `logging.performance.enabled=false`：默认关闭性能/健康相关的 stdout/stderr 输出（生产环境建议保持关闭）
- `logging.console.enabled=true`：控制 SleuthLogger 是否输出系统日志到控制台（写入 stderr，便于与 stdout 的用户输出/协议输出分层）

插桩日志与代理类覆盖：

- 默认允许对常见代理类（如 Spring/CGLIB `$$EnhancerBySpringCGLIB$$`）执行 watch/trace
- 仍会过滤噪音类（如 `$$Lambda$`）
- `logging.level=DEBUG` 时会输出每次 transform 的增强日志，`INFO` 默认不刷屏

日志/输出分层说明：

- **用户交互输出**：Launcher 的命令输出/提示走 stdout；Agent/Server 协议回写走 Socket
- **系统日志**：统一通过 `SleuthLogger` 输出到 stderr（前缀 `SLEUTH:`），并在命令执行时自动携带上下文字段（clientId/sessionId/connId/command，token 默认脱敏）

命令与安全细节补充：

- `session` 命令默认脱敏 `SessionId`，如需排查可显式执行 `session --show-token`（敏感信息，请谨慎）
- `sm -E`（regex）使用 RE2/J 作为引擎以避免灾难性回溯（ReDoS），但与 Java 原生 regex 行为可能存在差异（例如不支持回溯/反向引用等特性）

## Commands Reference

### Phase 1 Commands

#### Dashboard Command
```bash
sleuth> dashboard
```
Displays comprehensive JVM information including:
- JVM details (name, version, uptime)
- Memory usage (heap and non-heap)
- Thread statistics
- Class loading information
- Garbage collection statistics

#### Thread Command
```bash
sleuth> thread              # Show all threads
sleuth> thread 123          # Show details for thread ID 123
sleuth> thread -b           # Show only blocked threads
sleuth> thread -d           # Show deadlock information
```

#### Search Class Command
```bash
sleuth> sc *Service*        # Find classes containing "Service"
sleuth> sc com.example.*    # Find classes in com.example package
sleuth> sc MyClass -d       # Show class details
sleuth> sc MyClass -f       # Show class fields
sleuth> sc MyClass -x       # Expand all details
```

#### Search Method Command
```bash
sleuth> sm *Service* get*   # Find methods starting with "get" in Service classes
sleuth> sm MyClass -d       # Show method details with signatures
sleuth> sm MyClass -E       # Enable regex pattern matching
```

### Phase 2 Commands

#### Watch Command
```bash
sleuth> watch com.example.* execute*              # Watch all execute methods
sleuth> watch *Service* *method* -n 50 -t 60      # Watch with limits
sleuth> watch MyClass doWork --no-params          # Don't capture parameters
```
**Options:**
- `-n, --count <num>`: Maximum events to capture (default: 100)
- `-t, --timeout <sec>`: Timeout in seconds (default: 30)
- `--no-params`: Don't capture method parameters
- `--no-return`: Don't capture return values
- `--no-exception`: Don't capture exceptions

#### Trace Command
```bash
sleuth> trace com.example.* execute*              # Trace method call chains
sleuth> trace *Service* *method* -d 5 -n 50       # Limit depth and count
sleuth> trace MyClass doWork -t 60                # Set timeout
```
**Options:**
- `-d, --depth <num>`: Maximum trace depth (default: 10)
- `-n, --count <num>`: Maximum events to capture (default: 100)
- `-t, --timeout <sec>`: Timeout in seconds (default: 30)

### Phase 3 Commands

#### Memory Compiler (mc) Command
```bash
sleuth> mc MyClass.java                           # Compile in memory
sleuth> mc src/Service.java -c com.example.Service  # Specify class name
sleuth> mc MyClass.java -o ./target/classes -v    # Save to disk with verbose
```
**Options:**
- `-c, --class <name>`: Specify fully qualified class name
- `-o, --output <dir>`: Output directory to save .class files
- `-v, --verbose`: Show detailed compilation information

#### Redefine Command
```bash
sleuth> redefine com.example.MyClass /path/to/MyClass.class
sleuth> redefine com.example.Service ./target/Service.class -v
```
**Options:**
- `-v, --verbose`: Show detailed information

**Limitations:**
- Cannot change class structure (add/remove methods or fields)
- Cannot change method signatures
- Can only modify method implementations

#### Retransform Command
```bash
sleuth> retransform com.example.*                 # Retransform matching classes
sleuth> retransform *Service* --list              # List without retransforming
sleuth> retransform MyClass -v                    # Verbose output
```
**Options:**
- `-l, --list`: List matching classes without retransforming
- `-v, --verbose`: Show detailed information

## Workflows

### Hot Code Reload Workflow
1. **Modify source code** in your Java file
2. **Compile**: `sleuth> mc MyClass.java -o ./target/classes`
3. **Hot-swap**: `sleuth> redefine com.example.MyClass ./target/classes/MyClass.class`
4. **Verify**: The running application now uses the new implementation

### Method Monitoring Workflow
1. **Watch methods**: `sleuth> watch com.example.* method* -n 20`
2. **Trigger the functionality** in your application
3. **Observe execution**: View parameters, return values, and timing
4. **Stop monitoring**: Press Ctrl+C or wait for timeout

### Performance Analysis Workflow
1. **Trace execution**: `sleuth> trace com.example.Service process* -d 5`
2. **Analyze call chains**: See method call hierarchy and timing
3. **Identify bottlenecks**: Find slow methods and call patterns
4. **Optimize and retransform**: Apply fixes and retransform classes

## Architecture

Java-Sleuth 采用类似 Arthas 的“两段式 Agent”，目标是避免在目标 JVM 内发生依赖碰撞：

### 控制端（目标 JVM 外）
- **java-sleuth-launcher**：CLI 启动器，负责 JVM 发现与 Attach 注入

### 目标 JVM 内
- **java-sleuth-agent（bootstrap）**：薄 Agent（agentmain/premain 入口），追加到 BootstrapClassLoader 搜索路径。
- **java-sleuth-agent-core**：实现 fat-jar（ASM/Jackson/CFR/RE2J...），由 bootstrap 使用隔离 ClassLoader 加载，从而不把三方依赖暴露给业务 ClassLoader。
- **Spy/Bridge（`com.javasleuth.monitor.*`）**：被插桩字节码直接引用的回调，保证“注入字节码只依赖 JDK + sleuth 自己的稳定 API”，避免把 ASM/Jackson 等泄漏进业务依赖面。

### 运行时核心（位于 agent-core 内）
- **CommandProcessor**：基于 Socket 的命令处理，多客户端支持
- **SleuthClassFileTransformer**：字节码转换框架

### Enhancement Framework
- **WatchEnhancer**: Method monitoring with parameter/return capture
- **TraceEnhancer**: Method call chain tracing with timing
- **WatchInterceptor**: Runtime interception and data collection
- **TraceInterceptor**: Call depth tracking and timing analysis

### Compilation System
- **MemoryJavaCompiler**: In-memory Java source compilation
- **ClassDefinition**: Hot-swapping and redefinition support

## Project Structure
```
foundation/src/main/java/com/javasleuth/
├── command/protocol/   # 协议编解码（bootstrap 可见）
├── monitor/            # 插桩回调 Spy/Bridge（bootstrap 可见）
├── security/           # 安全/审计
├── data/               # 核心数据结构
└── util/               # 工具类（JarLocator 等）

agent/src/main/java/com/javasleuth/agent/
└── SleuthAgent.java    # Bootstrap agent 入口（薄、仅依赖 JDK）

core/src/main/java/com/javasleuth/
├── agent/core/         # SleuthAgentCore 入口（由隔离 ClassLoader 加载）
├── command/            # 命令系统与实现
├── enhancement/        # 字节码增强框架
├── compiler/           # 内存编译（mc）
└── util/               # 工具类（jad/decompiler 等）

launcher/src/main/java/com/javasleuth/launcher/
└── SleuthLauncher.java # CLI Launcher（Attach API + 交互会话）

examples/src/main/java/com/javasleuth/test/
├── TestApplication.java
└── EnhancedTestApplication.java
```

## Build Configuration

Maven 配置关键点：
- **Java 8 兼容**：支持 JDK 8+ 环境
- **Agent Manifest**：仅 bootstrap agent jar 声明 Java Agent 入口
- **Fat JAR 打包**：分别构建 launcher / agent-bootstrap / agent-core 三个 fat-jar
- **跨平台脚本**：Linux/macOS/Windows 支持

## Troubleshooting

### Agent JAR Not Found
```
Agent JAR not found: agent/target/java-sleuth-agent-*-jar-with-dependencies.jar
Agent CORE JAR not found: core/target/java-sleuth-agent-core-*-jar-with-dependencies.jar
Please build the project first with: mvn clean package
```
**Solution**:
- Run `mvn clean package` to build the project.
- Or set `-Dsleuth.agent.jar=<path>` (or env `SLEUTH_AGENT_JAR`) to point to the bootstrap agent jar directly.
- For the two-stage agent, also set `-Dsleuth.agent.core.jar=<path>` (or env `SLEUTH_AGENT_CORE_JAR`) to point to the agent core jar.

### tools.jar Not Found (Java < 9)
```
tools.jar not found. Please set JAVA_HOME correctly for JDK < 9
```
**Solution**: Ensure `JAVA_HOME` points to a JDK installation (not JRE) for Java versions below 9.

### Connection Failed
```
Failed to connect to agent: Connection refused
```
**Solution**: Ensure the target JVM is still running and the agent was successfully attached.

### Compilation Issues (mc command)
```
Java compiler not available. Make sure you're running on a JDK, not JRE.
```
**Solution**: Use JDK (not JRE) for memory compilation features.

### Redefinition Limitations
```
Redefinition failed: attempted to change the schema
```
**Solution**: Class redefinition cannot change class structure. Only method implementations can be modified.

## Performance Considerations

- **Minimal overhead**: Instrumentation only active when monitoring is enabled
- **Selective enhancement**: Only target specific classes/methods to minimize impact
- **Efficient data capture**: Asynchronous collection with bounded queues
- **Memory management**: Automatic cleanup when monitoring stops

## License

[Add your license information here]

## Contributing

[Add contribution guidelines here]
