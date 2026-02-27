# Debugging in IntelliJ IDEA (JDK 21)

本文描述如何在 IntelliJ IDEA 中调试 Java-Sleuth，尤其是完整链路下涉及的 **两进程** 调试方式。

> 关键点：一次完整链路会同时涉及两个 JVM 进程：  
> 1) **Launcher 进程**（你在 IDEA 里启动的 `SleuthLauncher`）  
> 2) **目标 JVM**（被 attach 的应用进程；agent 的逻辑在这里运行）  
>
> 因此你通常需要在 IDEA 里开 **两个 Debug 会话**：分别调试 Launcher 与目标 JVM。

---

## 0. 常见编译报错：`com.sun.tools.attach` 不存在

如果你在 IDEA 里运行/Debug `com.javasleuth.launcher.SleuthLauncher` 时看到类似错误：

```text
java: package com.sun.tools.attach does not exist
```

常见原因是：IDEA 使用了 `javac --release 8` 做交叉编译，导致 Attach API（非 Java SE 标准 API）在编译期不可见。

处理方式（推荐）：

1. 打开 IDEA 设置（Settings/Preferences）
2. 进入：`Build, Execution, Deployment` → `Compiler` → `Java Compiler`
3. 取消勾选：`Use '--release' option for cross-compilation (Java 9 and later)`（不同版本文案略有差异，关键词一般是 `--release`）
4. 重新导入 Maven 并 Rebuild

---

## 1. 先构建一次（生成可被 Attach 的 jar）

Attach 需要真实的 agent jar 文件，因此建议先在项目根目录执行：

```bash
mvn clean package
```

然后确认关键产物存在（名字以实际构建输出为准）：

- `agent/target/java-sleuth-agent-*-jar-with-dependencies.jar`
- `container/target/java-sleuth-container-*-jar-with-dependencies.jar`
- （可选）`launcher/target/java-sleuth-launcher-*-jar-with-dependencies.jar`

---

## 2. Debug 会话 A：启动“目标 JVM”（示例应用）

建议用 `examples` 模块的示例应用作为被诊断目标，避免额外准备业务项目。

创建一个 Application 配置：

- Main class：`com.javasleuth.test.EnhancedTestApplication`
- Use classpath of module：`examples`
- JRE：选择 `JDK 21`

点击 **Debug** 启动该配置，让目标 JVM 保持运行。

源码位置参考：`examples/src/main/java/com/javasleuth/test/EnhancedTestApplication.java`。

---

## 3. Debug 会话 B：启动 Launcher（Attach + 交互）

Launcher 入口：

- Main class：`com.javasleuth.launcher.SleuthLauncher`
- 源码位置：`launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`

创建一个 Application 配置：

- Use classpath of module：`launcher`
- Working directory：项目根目录（很重要，便于定位 jar 与配置文件）
- VM options（JDK 9+ 建议显式开启 Attach 模块）：
  - `--add-modules jdk.attach`

启动后进入交互界面，选择目标 JVM 的 PID 并开始执行命令。

---

## 4. 断点该打在哪个进程？

- `launcher/` 下代码运行在 **Launcher JVM**（会话 B）
- `agent/` / `container/` / `core/` / `bootstrap/` 下代码运行在 **目标 JVM**（会话 A）

如果你想调试插桩生效链路（例如 `watch/trace/monitor/stack`），断点通常需要在目标 JVM 的 Debug 会话里命中。

---

## 5. 常见问题

- **断点不生效**：确认你重建过 jar（`mvn package`），且 attach 加载的是最新产物
- **找不到 jar**：优先把 Working directory 设为项目根目录，或按 `docs/usage/getting-started.md` 显式指定 jar 路径

