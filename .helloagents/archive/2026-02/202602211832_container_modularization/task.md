# Task List: Container 模块化 + 四大 God Class 职责收敛

Directory: `helloagents/plan/202602211832_container_modularization/`

---

## 1. Build / Module Boundary
- [√] 1.1 新增 `container` Maven 模块并加入聚合构建（`pom.xml`、`container/pom.xml`），定义依赖图（container -> core/foundation/bootstrap），verify why.md#scenario-maven-build-time-guard
- [√] 1.2 加入构建期依赖守护（Maven Enforcer 等）：禁止 `core` 依赖 `container`、保证 `foundation`/`bootstrap` JDK-only，verify why.md#requirement-module-boundary-hardening, depends on task 1.1
- [√] 1.3 调整打包产物：container 输出可被 bootstrap agent 加载的 fat-jar；core 回归为纯库 jar（或仅保留 library 产物），verify why.md#requirement-runtime-container-as-composition-root, depends on task 1.1

## 2. Container Runtime Wiring（Composition Root）
- [√] 2.1 新增 container 侧 entrypoint（例如 `com.javasleuth.agent.container.*`）并让 bootstrap agent 加载 container jar（更新 JarLocator/定位策略与反射入口），verify why.md#scenario-attach-run-detach-idempotent, depends on task 1.3
- [ ] 2.2 在 container 内实现 `SleuthRuntimeContainer`（或等价命名）统一装配：Config/Security/Command/Monitoring/Threading，并提供 `close()` 幂等回收路径，verify why.md#scenario-attach-run-detach-idempotent, depends on task 2.1
- [ ] 2.3 将 core 内的装配入口迁移/下沉到 container（替换 `CommandProcessorFactory` 的装配职责），并把“创建线程池/调度器/插件 classloader”收口到 container，verify why.md#requirement-runtime-container-as-composition-root, depends on task 2.2

## 3. core/command：Registry 与 Factory 拆分
- [ ] 3.1 重构 `core/src/main/java/com/javasleuth/command/CommandProcessor.java`：移除自举构造器（不再内部调用 Factory），仅保留显式注入 `CommandProcessorComponents` 的入口，verify why.md#requirement-runtime-container-as-composition-root, depends on task 2.3
- [ ] 3.2 重构 `core/src/main/java/com/javasleuth/command/CommandProcessorFactory.java`：迁出到 container 或删除（core 不再承担装配），并更新所有调用点（含测试），verify why.md#requirement-module-boundary-hardening, depends on task 3.1
- [√] 3.3 拆分 `core/src/main/java/com/javasleuth/command/CommandRegistry.java`：分离 Registry/Registrar/ConflictResolutionPolicy（prefer-builtin/prefer-plugin/fail 等显式类型化），verify why.md#requirement-command-plugin-loading-boundary, depends on task 2.3
- [-] 3.4 将 provider/插件加载从 registry 中剥离到 container（含 ServiceLoader 与插件目录扫描），registry 仅接收 providers 并完成注册，verify why.md#scenario-plugins-disabled-by-default--allowlist-sha256, depends on task 3.3
> Note: 已从 registry 中剥离 provider/插件加载到 `CommandProviderLoader`（当前仍位于 core 装配侧）；后续可继续迁移到 container 以完成模块级收口。

## 4. foundation/config：ProductionConfig 去中心化继续推进
- [ ] 4.1 进一步收敛 `foundation/src/main/java/com/javasleuth/config/ProductionConfig.java`：把“加载/校验/默认/派生规则/覆写审计”按组件拆分并形成可注入依赖（Typed Config/Snapshot/Store），verify why.md#scenario-request-level-config-snapshot
- [ ] 4.2 在容器边界处一次性解析强类型配置（如 `SleuthConfigParser`），并在命令执行入口传递 `ConfigView`/`ConfigSnapshot`，减少核心逻辑散落读 key，verify why.md#requirement-config-boundary-type-narrowing, depends on task 4.1

## 5. foundation/security：AuthenticationManager 拆分与生命周期收口
- [ ] 5.1 重构 `foundation/src/main/java/com/javasleuth/security/AuthenticationManager.java`：拆分为 Authenticator/SessionStore/AttemptTracker/CleanupScheduler（可 start/stop），并去除/收口单例入口到 container，verify why.md#scenario-session-cleanup-stops-on-detach
- [ ] 5.2 调整授权/危险命令确认/请求签名等组件的装配方式：避免在业务对象内隐式回退到 `getInstance()`，统一由 container 注入，verify why.md#requirement-authn-session-lifecycle-isolation, depends on task 5.1

## 6. Security Check
- [ ] 6.1 执行安全检查（G9）：插件供应链（allowlist/sha256）、敏感信息脱敏、权限控制、detach/reattach 状态清理，verify why.md#requirement-command-plugin-loading-boundary

## 7. Documentation Update（SSOT 同步）
- [√] 7.1 更新知识库：`helloagents/wiki/modules/command.md`、`helloagents/wiki/modules/security.md`、`helloagents/wiki/modules/config.md`，补齐 container 模块与新边界约束说明，verify why.md#requirement-module-boundary-hardening
- [√] 7.2 更新 `helloagents/CHANGELOG.md`（breaking change 标注、迁移说明、验证方式），depends on task 7.1

## 8. Testing
- [ ] 8.1 补齐/调整单测与集成测试（container 生命周期、插件拒绝审计、会话清理停机、命令注册冲突策略），verify why.md#scenario-attach-run-detach-idempotent
- [-] 8.2 运行全量验证：`mvn test`（root），并对关键场景做一次手工 smoke（启动/连接/help/auth/stop/detach→re-attach），depends on task 8.1
> Note: 已完成 `mvn test`（root）；手工 smoke 建议由本机环境执行（attach/detach 需要真实目标 JVM）。
