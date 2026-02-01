# Task List: 启动/安全/插件/Trace 综合加固（Hardening & Bootstrap）

Directory: `helloagents/history/2026-02/202602011222_sleuth_hardening_bootstrap/`

---

## 1. 启动/发布稳定化（去版本硬编码 + 任意 cwd 可启动）
- [√] 1.1 实现“agent jar 自动定位”能力：基于 `CodeSource` 定位自身 jar，IDE 运行回退扫描 `target/`，并支持 `sleuth.agent.jar`/`SLEUTH_AGENT_JAR` 覆盖；涉及 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java` + 新增 `src/main/java/com/javasleuth/util/JarLocator.java`，验证 why.md#requirement-启动与发布稳定化（bootstrap/packaging）-scenario-任意工作目录可启动并自动找到-jar
- [√] 1.2 统一 attach 使用的 agentPath：使用 JarLocator 结果替代硬编码 `AGENT_JAR_NAME`，并确保 jar 不存在时错误提示包含可操作指引；修改 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`，验证 why.md#requirement-启动与发布稳定化（bootstrap/packaging）-scenario-任意工作目录可启动并自动找到-jar，依赖 1.1
- [√] 1.3 修复 `sleuth.sh` 的跨平台/任意 cwd 启动：使用通配符定位 jar；`cd`/路径基准修复；取消 `grep -P`，改为 tools.jar 存在性探测；修改 `sleuth.sh`，验证 why.md#requirement-启动与发布稳定化（bootstrap/packaging）-scenario-macos-上无需-grep--p-也能正常运行
- [√] 1.4 修复 `sleuth.bat` 的 jar 定位与 tools.jar 处理：通配符定位 jar；仅在 tools.jar 存在时追加 classpath；修改 `sleuth.bat`，验证 why.md#requirement-启动与发布稳定化（bootstrap/packaging）-scenario-任意工作目录可启动并自动找到-jar
- [√] 1.5 清理脚本硬编码 jar 名称（demo/perf/security）：使用通配符 + 变量化 jar 路径；修改 `scripts/demo/demo.sh`、`scripts/demo/demo-comprehensive.sh`、`scripts/perf/performance-benchmark.sh`，验证 why.md#requirement-启动与发布稳定化（bootstrap/packaging）-scenario-任意工作目录可启动并自动找到-jar
- [√] 1.6 清理剩余脚本硬编码并确保 Windows/Unix 路径兼容：修改 `scripts/perf/performance-test.sh`、`scripts/security/security-test.sh`，验证 why.md#requirement-启动与发布稳定化（bootstrap/packaging）-scenario-任意工作目录可启动并自动找到-jar

## 2. 安全自举（HMAC secret 自动下发 + 移除硬编码口令）
- [√] 2.1 新增安全自举配置项并提供默认行为：补充 `security.bootstrap.*` / `security.auth.*` / `security.hmac.session.role` 等配置与读取方法；修改 `src/main/resources/sleuth-default.properties`、`src/main/java/com/javasleuth/config/ProductionConfig.java`，验证 why.md#requirement-安全默认与认证自举（secure-by-default）-scenario-attach-自动启用-hmac-签名（无需用户手工配置-secret）
- [√] 2.2 Launcher attach 时生成并下发 HMAC secret，同时让 Launcher 侧同步启用签名发送（避免 Agent 与 Launcher 配置不一致）：修改 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`、`src/main/java/com/javasleuth/security/RequestSecurityManager.java`（必要时新增签名模式提示），验证 why.md#requirement-安全默认与认证自举（secure-by-default）-scenario-attach-自动启用-hmac-签名（无需用户手工配置-secret），依赖 2.1
- [√] 2.3 移除 `AuthenticationManager` 中的硬编码 demo 口令，改为“显式配置/环境变量”并提供开发开关：修改 `src/main/java/com/javasleuth/security/AuthenticationManager.java`、`src/test/java/com/javasleuth/security/AuthenticationManagerTest.java`，验证 why.md#requirement-安全默认与认证自举（secure-by-default）-scenario-移除硬编码-demo-口令，避免“生产默认口令”
- [√] 2.4 当 `security.mode=hmac` 时支持“免口令会话角色”：按 `security.hmac.session.role` 创建 session 并与 AuthorizationManager 对齐（避免必须 `auth` 才能执行命令）；修改 `src/main/java/com/javasleuth/command/CommandProcessor.java`、`src/main/java/com/javasleuth/security/AuthorizationManager.java`、`src/main/java/com/javasleuth/security/AuthenticationManager.java`，验证 why.md#requirement-安全默认与认证自举（secure-by-default）-scenario-attach-自动启用-hmac-签名（无需用户手工配置-secret）

## 3. 插件加载加固（默认关闭 + allowlist/sha256 + 释放 classloader）
- [√] 3.1 增加 `plugins.enabled` 默认 false，并在 registry 中按开关决定是否加载插件目录：修改 `src/main/resources/sleuth-default.properties`、`src/main/java/com/javasleuth/config/ProductionConfig.java`、`src/main/java/com/javasleuth/command/CommandRegistry.java`，验证 why.md#requirement-插件加载安全与资源释放（plugin-hardening）-scenario-插件默认关闭，启用需显式开关
- [√] 3.2 实现插件 allowlist 与（可选）sha256 校验：未通过校验直接拒绝并记录审计；修改 `src/main/java/com/javasleuth/command/CommandRegistry.java`、`src/main/java/com/javasleuth/security/AuditLogger.java`、`src/main/java/com/javasleuth/config/ProductionConfig.java`，验证 why.md#requirement-插件加载安全与资源释放（plugin-hardening）-scenario-allowlistsha256-校验通过才加载，依赖 3.1
- [√] 3.3 管理插件 classloader 生命周期并在 shutdown 释放资源；必要时增加 staging copy 方案降低 Windows 锁定影响：修改 `src/main/java/com/javasleuth/command/CommandRegistry.java`、`src/main/java/com/javasleuth/agent/SleuthAgent.java`，验证 why.md#requirement-插件加载安全与资源释放（plugin-hardening）-scenario-释放资源，避免-windows-jar-锁定，依赖 3.2

## 4. Trace 采样一致性修复（entry/exit 配对 + 默认更保守）
- [√] 4.1 重构 TraceInterceptor 采样为“调用级一致”：ThreadLocal 栈记录采样决定，确保 entry/exit/subcall 配对与 depth 一致；修改 `src/main/java/com/javasleuth/monitor/TraceInterceptor.java`（必要时新增 `src/main/java/com/javasleuth/monitor/TraceSamplingState.java`），验证 why.md#requirement-trace-采样正确性（sampling-consistency）-scenario-entryexit-采样一致，调用树稳定
- [√] 4.2 调整默认采样率为更保守值，并补充按命令覆盖参数（如 `trace --sample <rate>`）；修改 `src/main/resources/sleuth-default.properties`、`src/main/java/com/javasleuth/command/impl/TraceCommand.java`、`src/main/java/com/javasleuth/config/ProductionConfig.java`，验证 why.md#requirement-trace-采样正确性（sampling-consistency）-scenario-默认采样更保守，降低高-qps-影响，依赖 4.1
- [√] 4.3 统一 monitor 的采样配置（避免与 trace 共用同一 key 导致误调）：新增 `monitoring.monitor.sample.rate`（保留旧 key 兼容），修改 `src/main/java/com/javasleuth/monitor/MonitorInterceptor.java`、`src/main/java/com/javasleuth/config/ProductionConfig.java`、`src/main/resources/sleuth-default.properties`，验证 why.md#requirement-trace-采样正确性（sampling-consistency）-scenario-默认采样更保守，降低高-qps-影响

## 5. 日志/副作用治理（减少污染 stdout/stderr 与可配置审计输出）
- [√] 5.1 替换关键 `printStackTrace` 与直接 System 输出为 `SleuthLogger`（异常栈仅 DEBUG 输出）：修改 `src/main/java/com/javasleuth/agent/SleuthAgent.java`、`src/main/java/com/javasleuth/launcher/SleuthLauncher.java`、`src/main/java/com/javasleuth/command/CommandProcessor.java`，验证 why.md#requirement-可维护性与文档一致性（maintainability--docs）-scenario-统一日志与异常输出，避免目标-jvm-刷屏
- [√] 5.2 审计日志文件路径与控制台输出可配置：新增 `logging.audit.file/logging.security.file/logging.audit.console` 并实现降级策略；修改 `src/main/java/com/javasleuth/security/AuditLogger.java`、`src/main/resources/sleuth-default.properties`、`src/main/java/com/javasleuth/config/ProductionConfig.java`，验证 why.md#requirement-可维护性与文档一致性（maintainability--docs）-scenario-审计日志路径控制台输出可配置

## 6. 文档与知识库同步（消除漂移）
- [√] 6.1 更新 README：去除 jar 版本硬编码提示，补充“推荐 Launcher attach + HMAC 自举”说明；修改 `README.md`，验证 why.md#requirement-可维护性与文档一致性（maintainability--docs）-scenario-文档与实现对齐（mc/tt/命令清单）
- [√] 6.2 更新命令文档：修正 `mc` 用法为文件路径输入；补齐 `tt/jobs/reset/stop/session/perm/version/logger/dump/getstatic` 等实际内置命令；修正“Total 命令数”描述；修改 `docs/usage/commands.md`，验证 why.md#requirement-可维护性与文档一致性（maintainability--docs）-scenario-文档与实现对齐（mc/tt/命令清单）
- [√] 6.3 更新生产部署指南：补充安全默认、插件启用策略、审计日志路径与 HMAC 不加密的注意事项；修改 `docs/ops/production-deployment-guide.md`、`config-templates/production-sleuth.properties`，验证 why.md#requirement-安全默认与认证自举（secure-by-default）-scenario-attach-自动启用-hmac-签名（无需用户手工配置-secret）
- [√] 6.4 更新内部知识库（模块说明对齐）：更新 `helloagents/wiki/modules/launcher.md`、`helloagents/wiki/modules/security.md`、`helloagents/wiki/modules/command.md`，补齐 jar 定位、安全自举、插件策略，验证 why.md#requirement-可维护性与文档一致性（maintainability--docs）-scenario-文档与实现对齐（mc/tt/命令清单）
- [√] 6.5 更新内部知识库（性能与监控）：更新 `helloagents/wiki/modules/monitor.md`、`helloagents/wiki/modules/config.md`，补齐 trace/monitor 采样与默认值变化，验证 why.md#requirement-trace-采样正确性（sampling-consistency）-scenario-entryexit-采样一致，调用树稳定

## 7. 安全检查
- [√] 7.1 执行安全检查（输入校验、敏感信息脱敏、权限控制、插件供应链风险、文件路径安全、默认安全策略），覆盖本次新增/变更路径

## 8. 测试与回归
- [√] 8.1 新增/调整单测：jar 定位（jar/目录形态）、trace 采样配对、插件 allowlist/sha256、认证无默认口令；涉及 `src/test/java/com/javasleuth/launcher/SleuthLauncherTest.java`（新增）、`src/test/java/com/javasleuth/monitor/TraceAggregatorTest.java`（扩展）与相关测试文件，验证 why.md#requirement-trace-采样正确性（sampling-consistency）-scenario-entryexit-采样一致，调用树稳定
- [√] 8.2 运行 `mvn test` 并确认通过（若新增脚本回归，可执行 `scripts/test/test-all-commands.sh` 做端到端冒烟）
