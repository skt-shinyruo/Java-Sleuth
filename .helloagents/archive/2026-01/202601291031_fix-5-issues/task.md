# Task List: 修复 5 个核心实现问题（协议/安全/插桩重构）

Directory: `helloagents/history/2026-01/202601291031_fix-5-issues/`

---

## 1. launcher（进程选择与连接地址）
- [√] 1.1 修复进程列表过滤后的序号映射与可选范围：重写选择逻辑（展示序号=可选序号），修改 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`，验证 why.md#requirement-修复进程选择与连接地址-scenario-本地-attach-并进入交互会话
- [√] 1.2 连接地址解析：支持按 `server.bind.address`/协商结果选择连接 host，并对 `0.0.0.0` 回退到 `127.0.0.1`；修改 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java`，验证 why.md#requirement-修复进程选择与连接地址-scenario-本地-attach-并进入交互会话

## 2. protocol/transport（统一传输层与协议升级）
- [√] 2.1 引入统一的 UTF-8 行编解码（替代 BufferedReader/PrintWriter）：新增 `src/main/java/com/javasleuth/command/protocol/Utf8LineCodec.java`，验证 why.md#requirement-统一传输层与协议升级（消除混用缓冲风险）-scenario-握手协商并升级到二进制模式
- [√] 2.2 服务端改造：CommandProcessor 使用统一 Transport/状态机处理 HELLO/CONFIG/UPGRADE，避免流封装混用；修改 `src/main/java/com/javasleuth/command/CommandProcessor.java` 与 `src/main/java/com/javasleuth/command/protocol/*`，验证 why.md#requirement-统一传输层与协议升级（消除混用缓冲风险）-scenario-握手协商并升级到二进制模式
- [√] 2.3 客户端改造：SleuthLauncher 使用统一 Transport/状态机处理握手与升级；修改 `src/main/java/com/javasleuth/launcher/SleuthLauncher.java` 与 `src/main/java/com/javasleuth/command/protocol/*`，验证 why.md#requirement-统一传输层与协议升级（消除混用缓冲风险）-scenario-握手协商并升级到二进制模式
- [√] 2.4 文本模式最大行长度限制：在 Transport/Codec 层对单行输入设置上限（与 `protocol.frame.max.payload` 对齐或新增配置），修改 `src/main/java/com/javasleuth/command/protocol/Utf8LineCodec.java` 与 `src/main/java/com/javasleuth/command/CommandProcessor.java`，验证 why.md#requirement-补齐资源限制与超时控制-scenario-超长文本行被安全拒绝

## 3. security（安全默认策略与 RBAC 收敛）
- [√] 3.1 非回环 bind 的安全策略：当 `server.bind.address` 非回环且 `security.mode=off` 时拒绝启动或强制切换到 hmac；修改 `src/main/java/com/javasleuth/command/CommandProcessor.java` 与 `src/main/java/com/javasleuth/config/ProductionConfig.java`，验证 why.md#requirement-安全默认策略与-rbac-收敛-scenario-非回环绑定启动与安全保护
- [√] 3.2 sysprop 写入权限收紧：将写入改为显式子命令并要求 ADMIN；修改 `src/main/java/com/javasleuth/command/impl/SysPropCommand.java` 与 `src/main/java/com/javasleuth/security/AuthorizationManager.java`，验证 why.md#requirement-安全默认策略与-rbac-收敛-scenario-非回环绑定启动与安全保护
- [√] 3.3 默认匿名策略调整：默认关闭 anonymous viewer（或仅在 loopback 下允许）；更新 `src/main/resources/sleuth-default.properties` 与 `src/main/java/com/javasleuth/config/ProductionConfig.java`，验证 why.md#requirement-安全默认策略与-rbac-收敛-scenario-非回环绑定启动与安全保护
- [√] 3.4 修复 InputValidator 与真实命令格式不一致：修正 `redefine/mc/heapdump` 参数校验位置与后缀要求，修改 `src/main/java/com/javasleuth/security/InputValidator.java`，验证 why.md#requirement-修复输入校验与文件权限判断不匹配-scenario-redefine-在启用-input-validation-时可正常执行
- [√] 3.5 修复文件读写权限判断：将 `SecurityValidator.canAccessFile` 拆分为可读/可写语义并支持相对路径；更新调用方（heapdump、sysprop 等），修改 `src/main/java/com/javasleuth/security/SecurityValidator.java` 与 `src/main/java/com/javasleuth/command/impl/HeapDumpCommand.java`，验证 why.md#requirement-修复输入校验与文件权限判断不匹配-scenario-heapdump-支持相对路径且按写权限校验
- [√] 3.6 审计日志脱敏：对 `auth/config/sysprop` 等命令参数做脱敏，避免密码/secret/session 写入日志与控制台；修改 `src/main/java/com/javasleuth/security/AuditLogger.java` 与 `src/main/java/com/javasleuth/command/impl/AuthCommand.java`（如需调整返回/记录策略），验证 why.md#requirement-修复审计-控制台日志敏感信息泄露-scenario-auth-config-不泄露密码与-secret
- [√] 3.7 配置修改输出脱敏：`config set` / runtimeConfig 更新时对敏感 key 的 value 脱敏，修改 `src/main/java/com/javasleuth/config/ProductionConfig.java` 与 `src/main/java/com/javasleuth/command/impl/ConfigCommand.java`，验证 why.md#requirement-修复审计-控制台日志敏感信息泄露-scenario-auth-config-不泄露密码与-secret

## 4. authentication（锁定/限流与客户端标识解析修复）
- [√] 4.1 重构登录失败计数与锁定窗口：用结构化记录替代 `loginAttempts` 全量清空；修改 `src/main/java/com/javasleuth/security/AuthenticationManager.java`，验证 why.md#requirement-修复登录锁定-限流与客户端标识解析-scenario-连续失败后锁定并按时间解除
- [√] 4.2 修复客户端 key 解析：支持 `/ip:port`、IPv6、unknown；修改 `src/main/java/com/javasleuth/security/AuthenticationManager.java`，验证 why.md#requirement-修复登录锁定-限流与客户端标识解析-scenario-连续失败后锁定并按时间解除

## 5. command/runtime（资源限制与超时控制落地）
- [√] 5.1 落实 server.max.connections：在 accept/连接建立阶段按配置拒绝超限连接，并记录审计与 metrics；修改 `src/main/java/com/javasleuth/command/CommandProcessor.java`，验证 why.md#requirement-补齐资源限制与超时控制-scenario-超限连接被拒绝且不影响现有连接
- [√] 5.2 落实 performance.command.timeout：为命令执行增加超时控制（含 framed/binary/streaming 的行为定义），修改 `src/main/java/com/javasleuth/command/CommandPipeline.java` 与 `src/main/java/com/javasleuth/command/CommandProcessor.java`，验证 why.md#requirement-补齐资源限制与超时控制-scenario-长耗时命令按-timeout-中断并返回错误

## 6. util（性能维护策略修正）
- [√] 6.1 禁用默认 System.gc：将 PerformanceOptimizer 的强制 GC 改为可配置且默认关闭；修改 `src/main/java/com/javasleuth/util/PerformanceOptimizer.java` 与 `src/main/java/com/javasleuth/config/ProductionConfig.java`，验证 why.md#requirement-修正性能维护策略（禁用默认-system-gc）-scenario-默认不触发强制-gc-可配置开启

## 7. agent/enhancement（生命周期/插桩覆盖/日志降噪）
- [√] 7.1 Agent 线程生命周期：命令线程设为 daemon，并完善 shutdown 的空指针保护；修改 `src/main/java/com/javasleuth/agent/SleuthAgent.java`，验证 why.md#requirement-agent-生命周期-插桩覆盖与日志降噪-scenario-spring-cglib-代理类-watch-trace-可命中
- [√] 7.2 插桩过滤策略调整：放开对常见代理类的过滤（保留对 `$$Lambda$` 等噪音类过滤），并将 transform 日志纳入可配置等级；修改 `src/main/java/com/javasleuth/enhancement/SleuthClassFileTransformer.java` 与新增 `src/main/java/com/javasleuth/util/SleuthLogger.java`，验证 why.md#requirement-agent-生命周期-插桩覆盖与日志降噪-scenario-spring-cglib-代理类-watch-trace-可命中

## 8. Security Check
- [√] 8.1 执行安全检查（按 G9：输入校验、敏感信息处理、权限控制、非回环 bind 的安全策略、日志中避免泄露 secret、DoS 防护）

## 9. Documentation Update
- [√] 9.1 更新使用说明与命令文档（握手/升级/安全默认变化、sysprop/auth 变更、timeout/连接限制）：修改 `README.md` 与 `docs/usage/commands.md`
- [√] 9.2 修正文档与默认配置不一致（移除/更正 security.mode=tls 等未实现描述，补齐真实支持项）：修改 `src/main/resources/sleuth-default.properties` 与 `docs/ops/production-deployment-guide.md`
- [√] 9.3 同步内部知识库模块说明（协议/安全/性能维护策略变化）：更新 `helloagents/wiki/modules/command.md`、`helloagents/wiki/modules/security.md`、`helloagents/wiki/modules/util.md`、`helloagents/wiki/modules/config.md`

## 10. Testing
- [√] 10.1 增加协议握手/升级/最大行长度相关测试：新增/修改 `src/test/java/com/javasleuth/command/CommandProcessorTest.java`，覆盖握手协商、升级与超长输入拒绝
- [√] 10.2 增加认证锁定窗口与 key 解析测试：修改 `src/test/java/com/javasleuth/security/AuthenticationManagerTest.java`（如不存在则新增），覆盖锁定/解锁与客户端 key 解析
- [√] 10.3 增加输入校验与危险命令参数格式测试：新增/修改 `src/test/java/com/javasleuth/security/InputValidatorTest.java`，覆盖 redefine/mc/heapdump 校验一致性
- [√] 10.4 增加审计脱敏测试：新增/修改 `src/test/java/com/javasleuth/security/AuditLoggerTest.java`，验证 auth/config 不泄露敏感值
- [-] 10.5 增加连接数限制与命令 timeout 测试：新增/修改 `src/test/java/com/javasleuth/command/CommandProcessorTest.java`（连接数限制偏集成测试，当前未补充）
- [√] 10.6 回归测试：运行 `mvn test` 并记录结果（2026-01-29：✅通过）
