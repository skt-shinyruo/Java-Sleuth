# 模块：bootstrap

## 职责
- **Bootstrap 可见桥接层（spy/bridge，JDK-only）**：为字节码增强提供稳定的 bootstrap 可见入口，包根 `com.javasleuth.bootstrap.*`。
- **跨 ClassLoader SSOT**：提供 bootstrap 可见注册表/闸门（如 `CoreClassLoaderRegistry`、`SystemPropertyRollbackRegistry`），用于 attach/detach 生命周期一致性。
- **拦截器与数据模型**：提供 `monitor/*Interceptor` 与跨边界数据对象（如 `bootstrap/data/*`）。

## 行为规范
- **JDK-only 强约束**：通过 `maven-enforcer-plugin` 禁止引入任何依赖（含传递依赖）。
- **可见性约束**：该模块的类应由 `BootstrapClassLoader` 加载（由 `SleuthAgent` append bridge jar 实现）。
- **最小化暴露面**：仅包含桥接/拦截/数据模型/轻量工具；避免把 agent/container/core 的实现细节放入 bootstrap。

## 常用配置（Jar 定位相关）
- 禁用 CWD 相对目录扫描（可选加固；默认不改变行为）：`-Dsleuth.locator.allowCwdScan=false`
- 启用定位调试输出（输出到 `System.err`）：`-Dsleuth.locator.debug=true`
- jar 显式覆盖（生产推荐，避免依赖扫描）：
  - bootstrap agent jar：`-Dsleuth.agent.jar=/path/to/java-sleuth-agent-<version>-jar-with-dependencies.jar`（或 `SLEUTH_AGENT_JAR`）
  - agent core jar（旧架构兜底）：`-Dsleuth.agent.core.jar=/path/to/java-sleuth-agent-core-<version>-jar-with-dependencies.jar`（或 `SLEUTH_AGENT_CORE_JAR`）
  - agent container jar（新架构推荐）：`-Dsleuth.agent.container.jar=/path/to/java-sleuth-container-<version>-jar-with-dependencies.jar`（或 `SLEUTH_AGENT_CONTAINER_JAR`）

## 依赖关系
- **编译期依赖**：无（JDK-only）。
- **运行时加载方式**：通过 `Instrumentation#appendToBootstrapClassLoaderSearch` 将 bridge jar 追加到 bootstrap 搜索路径。

## 构建产物
- `bootstrap/target/java-sleuth-bootstrap-<version>.jar`：bootstrap 模块原始 jar。
- `agent/target/java-sleuth-bootstrap-bridge-<version>.jar`：供运行时 append 的 bridge jar（由 agent 打包阶段复制）。

## 参考（legacy）
- `wiki/modules/bootstrap.md`
