# 运行时 Attach 与字节码增强（基础教学）

本文用于解释 Java-Sleuth 在“已运行 JVM”中动态注入 agent，并通过 `Instrumentation`/`ClassFileTransformer` 触发字节码增强的基本机制。

> 提示：本文偏“原理解释”。如果你只想快速使用，请直接看 `docs/usage/getting-started.md`。

---

## 1. 先区分两个进程

一次典型使用会同时涉及两个 JVM 进程：

- **Launcher 进程（本机 CLI）**：你运行 `./sleuth.sh` 启动的 Java 进程。它负责列出可 attach 的 JVM、执行 attach/loadAgent，并作为客户端连接目标 JVM 的命令服务。
- **目标 JVM（被诊断的进程）**：你选择 PID 的那个 Java 进程。agent 代码最终运行在这里，字节码增强也发生在这里。

---

## 2. Attach 注入阶段：`loadAgent(...)` 做了什么

Launcher 会对目标 JVM 执行标准 Attach 流程，其关键步骤是：

1. `VirtualMachine.attach(pid)`：连接到目标 JVM。
2. `VirtualMachine.loadAgent(agentJarPath, agentArgs)`：要求目标 JVM 动态加载 agent JAR，并把参数字符串传入。

对目标 JVM 来说：

- 运行时 attach 通常会触发 agent 的 `agentmain(String agentArgs, Instrumentation inst)`。
- 若以 `-javaagent` 的方式随 JVM 启动加载，则触发 `premain(...)`（有些项目会让 `premain` 复用 `agentmain`，以减少重复逻辑）。

`agentArgs` 通常会被解析为系统属性（例如端口/协议/安全模式等），用于后续 agent 初始化与运行。

---

## 3. Agent 初始化阶段：注册 Transformer 与启动命令处理

当 `agentmain(...)` 被触发后，agent 通常会做两类关键初始化：

1. **注册 `ClassFileTransformer`**  
   通过 `Instrumentation#addTransformer(transformer, true)` 把 transformer 挂到 JVM 的类加载/重转换链路上。  
   `true` 的含义是：允许该 transformer 参与后续的 **retransform（类重转换）**。

2. **启动命令处理线程/服务**  
   Java-Sleuth 会在目标 JVM 内启动命令处理线程（并对外提供端口服务），Launcher 后续会连接这个端口，发送 `watch/trace/...` 等命令并接收输出。

---

## 4. “真正改类”发生在什么时候？

注意：**注册 transformer 并不等于立刻修改所有类。**

JVM 只有在需要处理某个类时才会回调 `ClassFileTransformer#transform(...)`，而“真正的字节码修改”只会在以下条件同时满足时发生：

- `transform(...)` 被触发（类加载或重转换时）。
- transformer 内部判定该类需要增强，并 **返回非空的新字节数组**（返回 `null` 表示这次不改）。

在 Java-Sleuth 的使用场景下，触发增强通常来自两条路径：

1. **类首次加载（define/load）**  
   attach 之后，新加载的类会经过 transformer；如果该类已注册了增强器（enhancer），就会在加载时被增强。

2. **类重转换（retransform）**  
   这是诊断工具常用的“立即生效”手段：命令（如 `watch/trace/stack/monitor/tt`）会先注册 enhancer，然后调用 `Instrumentation#retransformClasses(...)`，让“已经加载过的类”重新走一遍 `transform(...)`，从而立刻套用增强逻辑。

---

## 5. 为什么要做“重复 attach 保护”？

运行时 attach 可能被重复触发（例如同一台机器上多次运行 Launcher 选择了同一个 PID，或多个运维同事同时 attach）。  
因此 agent 入口通常会用原子标志位（例如 `AtomicBoolean.compareAndSet(false, true)`）保护：

- 第一次 attach：允许初始化（注册 transformer、启动服务等）。
- 第二次及以后：直接提示“already attached”并返回，避免重复注册 transformer/重复启动线程造成副作用。

如果需要回滚/清理，通常会提供 `stop/reset` 一类命令：移除增强器、触发 retransform 尝试恢复原始字节码状态，并移除 transformer（best-effort）。

