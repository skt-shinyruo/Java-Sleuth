# Project Technical Conventions
# Java-Sleuth 项目概览（SSOT）

## 1. 项目目标

Java-Sleuth 是一个以 **Java Agent + Attach + 命令协议** 为核心的 JVM 诊断工具链：

- 通过 `launcher` 在本机选择目标 JVM → Attach → 动态加载 Agent
- Agent 在目标 JVM 内启动命令服务端（Command Server）
- `launcher` 作为客户端与服务端握手后进行交互（交互式 / 无交互脚本化）

核心设计目标：

- **对被诊断应用“低侵入”**：尽量减少对业务类路径与依赖解析的影响
- **强兼容性**：目标 JVM / 业务依赖版本差异下仍尽量稳定运行
- **可演进性**：将“编排/生命周期/协议/UI/Attach”等拆分为可测试组件，避免 God class

## 2. Maven 模块划分

当前为多模块 Maven 工程，按职责边界拆分：

- `foundation/`：基础设施与通用能力（协议 framing、util、security、monitor 等）
- `agent/`：**Java Agent Bootstrap**（入口 `com.javasleuth.agent.SleuthAgent`）
  - 尽量保持最小依赖，仅负责 **引导加载 agent-core**
- `core/`：**Java Agent Core**（入口 `com.javasleuth.agent.core.SleuthAgentCore`）
  - 运行在目标 JVM 内，包含命令服务端与诊断能力的核心实现
- `launcher/`：本机启动器/客户端（入口 `com.javasleuth.launcher.SleuthLauncher`）
  - 支持交互模式与 headless 模式
- `examples/`：示例应用与演示用工程

## 3. 依赖隔离（Why it matters）

Java Agent 的第三方库（如 ASM/Jackson/JLine/CFR 等）如果以原包名与业务依赖混在同一类加载器可见范围内，容易出现：

- 版本碰撞（`NoSuchMethodError` / `LinkageError` / 行为差异）
- 依赖解析不确定性（取决于类加载器的 parent-first 顺序与 classpath 排列）
- 对目标 JVM 的“类路径污染”与可预期性下降

本项目采用 **bootstrap → core 的隔离加载** 思路，目标是把 agent 依赖面尽可能从业务类加载器视野中隔离开（详见 `helloagents/wiki/modules/agent.md`）。

## 4. 构建与验证

- 运行单元测试：`mvn test`
- 仅打包（跳过测试）：`mvn -DskipTests package`

构建产物（典型）：

- `agent/target/java-sleuth-agent-<version>-jar-with-dependencies.jar`
- `core/target/java-sleuth-agent-core-<version>-jar-with-dependencies.jar`（用于隔离加载的 core 能力集合）
- `launcher/target/java-sleuth-launcher-<version>-jar-with-dependencies.jar`

## 5. 运行入口（高层）

推荐使用脚本入口（自动选择正确的 jar/参数）：

- Linux/macOS：`./sleuth.sh`
- Windows：`sleuth.bat`

补充：`launcher` 的 headless 模式支持 `--pid` + `--cmd/--script` 进行脚本化执行；涉及降低安全边界的参数需显式二次确认（详见 `helloagents/wiki/modules/launcher.md`）。
---

## Tech Stack
- **Core:** Java 8 / Maven 3
- **Libraries:** ASM 9.x、JLine 3.x、Jackson 2.x、CFR（jad）、RE2/J（安全 regex）
- **Runtime:** Attach API、JMX（可选）

---

## Development Conventions
- **Code Standards:** 维持现有风格与命名习惯，避免引入新的格式化规则
- **Naming Conventions:** Java 类/方法使用驼峰命名；命令名使用小写
- **Java Baseline:** 运行时基线为 Java 8；构建期通过 `mvn verify` 的 Java 8 API 校验避免误用 Java 9+/11+ API

---

## Errors and Logging
- **Strategy:** 命令执行失败返回可读错误信息，避免影响目标 JVM 稳定性
- **Logging:** 控制台日志 + 审计日志（可通过配置关闭）

---

## Testing and Process
- **Testing:** JUnit 4 单测为主；脚本覆盖命令回归
- **Commit:** 未定义统一规范，建议保持简洁可追溯
