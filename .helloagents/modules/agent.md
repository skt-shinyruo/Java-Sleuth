# 模块：agent

## 职责
- **Java Agent 引导入口**（`com.javasleuth.agent.SleuthAgent`）：负责 premain/agentmain 入口与启动链路编排。
- **最小化 bootstrap 可见面**：仅将独立的 bootstrap bridge jar append 到 `BootstrapClassLoader`（而不是 append agent 自身 jar）。
- **隔离加载 runtime**：定位并用隔离 `URLClassLoader(parent=null)` 加载 `container` fat-jar，再由 container 装配启动 core。
- **attach 闸门兜底**：当 bootstrap registry 不可用时，使用 `BootstrapAttachGate` 做 CAS 兜底（避免重复 attach）。

## 行为规范
- **JDK-only**：agent 模块保持无编译期依赖（仅 JDK），避免把三方库/业务依赖引入引导层。
- **append 规则**：只 append `java-sleuth-bootstrap-bridge-*.jar`（仅包含 `com.javasleuth.bootstrap.*`）。
- **fail-fast**：若 bootstrap bridge 不可用（关键类无法由 `BootstrapClassLoader` 加载），必须 fail-fast，避免后续增强注入导致目标 JVM 运行时 `NoClassDefFoundError/LinkageError`。

## 常用配置（定位/bridge 相关）
- bootstrap bridge jar 显式指定（推荐用于生产，避免兜底扫描不确定性）：
  - System property：`-Dsleuth.agent.bootstrap.bridge.jar=/path/to/java-sleuth-bootstrap-bridge-<version>.jar`
  - Env：`SLEUTH_AGENT_BOOTSTRAP_BRIDGE_JAR=/path/to/java-sleuth-bootstrap-bridge-<version>.jar`
- agent container jar 显式指定（新架构推荐）：
  - System property：`-Dsleuth.agent.container.jar=/path/to/java-sleuth-container-<version>-jar-with-dependencies.jar`
  - Env：`SLEUTH_AGENT_CONTAINER_JAR=/path/to/java-sleuth-container-<version>-jar-with-dependencies.jar`
- 禁用 CWD 相对目录扫描（可选加固；默认不改变行为）：`-Dsleuth.locator.allowCwdScan=false`
- 启用定位调试输出（输出到 `System.err`）：`-Dsleuth.locator.debug=true`（或 `-Dsleuth.agent.bootstrap.debug=true`）

## 依赖关系
- **编译期依赖**：无（JDK-only）。
- **运行时协作**：
  - 通过 append 的 bridge jar 使用 `com.javasleuth.bootstrap.*`（bootstrap 可见 SSOT）。
  - 通过隔离 ClassLoader 加载 `com.javasleuth.container.*`（container fat-jar）。

## 构建产物
- `agent/target/java-sleuth-agent-<version>-jar-with-dependencies.jar`：bootstrap agent 入口 jar（包含 `SleuthAgent`）。
- `agent/target/java-sleuth-bootstrap-bridge-<version>.jar`：bootstrap bridge jar（打包时从 `bootstrap` 模块复制，供运行时 append）。

## 参考（legacy）
- `wiki/modules/agent.md`
