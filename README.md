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

## Requirements

- **Java 8+** (JDK required for tools.jar on Java < 9)
- **Maven 3.6+** for building
- **Linux/macOS/Windows** support

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

Java-Sleuth consists of several key components:

### Core Components
- **SleuthAgent**: Java agent with agentmain/premain entry points
- **SleuthLauncher**: JVM discovery and dynamic attachment via Attach API
- **CommandProcessor**: Socket-based command handling with multi-client support
- **SleuthClassFileTransformer**: Bytecode transformation framework

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
src/main/java/com/javasleuth/
├── agent/          # Java agent implementation
├── launcher/       # JVM discovery and attachment
├── command/        # Command system
│   └── impl/       # Individual commands (dashboard, thread, watch, trace, etc.)
├── enhancement/    # Bytecode enhancement framework
├── monitor/        # Method interception and monitoring
├── data/           # Data structures for monitoring results
├── compiler/       # Memory compilation system
├── util/           # Utility classes
└── test/           # Test application
```

## Build Configuration

Maven configuration with key features:
- **Java 8 compatibility**: Works with JDK 8+ environments
- **Agent manifest**: Proper Java agent configuration
- **Fat JAR assembly**: Single executable with all dependencies
- **Cross-platform scripts**: Linux/macOS/Windows support

## Troubleshooting

### Agent JAR Not Found
```
Agent JAR not found: java-sleuth-1.0.0-jar-with-dependencies.jar
Please build the project first with: mvn clean package
```
**Solution**: Run `mvn clean package` to build the project.

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