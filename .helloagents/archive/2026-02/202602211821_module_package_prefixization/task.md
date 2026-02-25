# Task List: bootstrap/foundation/core 模块包根前缀化（消除 split package）

Directory: `helloagents/history/2026-02/202602211821_module_package_prefixization/`

---

## 1. bootstrap（bridge/spy 包根迁移）
- [√] 1.1 迁移 `AgentArgsApplier/JarLocator` 到 `com.javasleuth.bootstrap.util`（移动源码目录 + 更新 package/import），verify why.md#requirement-r1---split-package-消除--bootstrap-边界显式化
- [√] 1.2 迁移 `RingBuffer/SleuthValue*` 到 `com.javasleuth.bootstrap.util`，verify why.md#requirement-r2---core-不依赖-bootstrap-的实现细节
- [√] 1.3 迁移 `monitor` 包到 `com.javasleuth.bootstrap.monitor`（逐文件迁移并保持对外静态 API 不变），verify why.md#requirement-r3---增强字节码引用同步迁移
- [√] 1.4 迁移 `data` 包到 `com.javasleuth.bootstrap.data`，并更新 core/launcher 引用，verify why.md#requirement-r3---增强字节码引用同步迁移
- [√] 1.5 运行 `mvn -pl bootstrap -DskipTests=false test` 确认 bootstrap 编译与单测通过（如无单测则至少 compile），depends on 1.1-1.4

## 2. foundation（基础设施包根迁移）
- [√] 2.1 迁移 `util` 包到 `com.javasleuth.foundation.util`（优先 SleuthLogger/SleuthExecutors/SleuthThreadFactory），verify why.md#requirement-r1---split-package-消除--bootstrap-边界显式化
- [√] 2.2 迁移 `config` 包到 `com.javasleuth.foundation.config`（含 model 子包），verify why.md#requirement-r1---split-package-消除--bootstrap-边界显式化, depends on 2.1
- [√] 2.3 迁移 `security` 包到 `com.javasleuth.foundation.security`，verify why.md#requirement-r1---split-package-消除--bootstrap-边界显式化, depends on 2.1
- [-] 2.4 迁移 `compiler` 包到 `com.javasleuth.foundation.compiler`（如存在），depends on 2.1
  > Note: foundation 模块当前无 `compiler` 包，无需迁移
- [√] 2.5 运行 `mvn -pl foundation -DskipTests=false test` 确认 foundation 编译通过，depends on 2.1-2.4

## 3. core（实现包根迁移 + 去耦 bootstrap 细节）
- [√] 3.1 迁移 `core util` 包到 `com.javasleuth.core.util`，并调整 core 内引用，verify why.md#requirement-r2---core-不依赖-bootstrap-的实现细节
- [√] 3.2 迁移 `command` 包到 `com.javasleuth.core.command`（先 core 顶层与 pipeline/server/protocol 子包），depends on 3.1
- [√] 3.3 迁移 `enhancement` 包到 `com.javasleuth.core.enhancement`，并同步更新 ASM internalName/owner 常量，verify why.md#requirement-r3---增强字节码引用同步迁移, depends on 1.3-1.4
- [√] 3.4 迁移 `vmtool/monitoring` 包到 `com.javasleuth.core.vmtool` / `com.javasleuth.core.monitoring`，depends on 3.2
- [√] 3.5 迁移 core 内部 `agent.core/agent.runtime` 到 `com.javasleuth.core.agent.*`，并更新 `agent` 侧入口类字符串，depends on 3.2
- [√] 3.6 更新 core 单测包名与 import（尤其是 util/bootstrap bridge 相关测试），depends on 1.1-1.4, 2.1-2.4, 3.1-3.5
- [√] 3.7 运行 `mvn -pl core -am -DskipTests=false test` 确认 core 编译与测试通过，depends on 3.6

## 4. agent/launcher（入口与引用同步）
- [√] 4.1 更新 `agent` 的 container/core 入口类字符串到新包根，并确保 append bootstrap 行为不变，depends on 3.5
- [√] 4.2 更新 `launcher` 对 `JarLocator/SleuthLogger` 等类的 import 引用到新包根，depends on 1.1, 2.1
- [√] 4.3 运行 `mvn -pl agent,launcher -am -DskipTests=false test` 确认编译与测试通过，depends on 4.1-4.2

## 5. Security Check
- [√] 5.1 执行安全自检（G9）：确认 bootstrap 仍为 JDK-only；确认不存在把 foundation/core 包误暴露到 bootstrap；检查反射/字符串类名/ASM internalName 是否漏改

## 6. Documentation Update
- [√] 6.1 更新 `helloagents/wiki/modules/bootstrap.md`：反映新的 bootstrap 包根与可见面约束
- [√] 6.2 更新 `helloagents/wiki/modules/util.md`：更新 util 的模块归属与包名
- [√] 6.3 必要时更新 `helloagents/project.md` 中关于隔离与边界的描述（保持 SSOT 一致）

## 7. Testing
- [√] 7.1 执行根工程 `mvn test` 做全量回归（至少覆盖所有模块编译与单测），确认无 split package 残留
- [-] 7.2 端到端冒烟：本地启动示例进程 → attach → 执行 `status/help` 等基础命令验证启动链路无类加载错误（需要本机真实目标 JVM 环境）
