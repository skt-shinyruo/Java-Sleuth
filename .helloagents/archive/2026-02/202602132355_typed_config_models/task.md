# Task List: 强类型配置模型（Typed Config Models）

Directory: `helloagents/history/2026-02/202602132355_typed_config_models/`

---

## 1. foundation/config（强类型模型 + 解析校验）
- [√] 1.1 新增强类型配置对象骨架（`SleuthConfig`/`ProtocolConfig`/`ServerConfig`/`SecurityConfig`）到 `foundation/src/main/java/com/javasleuth/config/model/*`，verify why.md#requirement-配置读取强类型化与默认值收敛-scenario-协议相关配置读取（server-与-launcher-一致）
- [√] 1.2 新增集中解析与校验入口（如 `SleuthConfigParser`）：从 `ConfigView`/`ConfigSnapshot` 解析出强类型配置并做 enum/range/blank 校验；包含 `textMaxLineBytes` 等“派生默认”规则，verify why.md#requirement-启动会话级解析一次并校验-scenario-建立连接后进入握手与命令处理，depends on task 1.1
- [√] 1.3 默认值收敛：抽取集中默认入口（如 `SleuthDefaults.apply(Properties)`）并让 `DefaultConfigFallback` 复用，避免手写默认散落，verify why.md#requirement-默认值一致性可自动验证-scenario-ci-运行一致性测试，depends on task 1.2

## 2. core/protocol（握手/连接边界改造）
- [√] 2.1 修改 `core/src/main/java/com/javasleuth/command/server/protocol/HandshakeNegotiator.java`：构造注入强类型配置（或 `SleuthConfig` 子视图），消除散落 key+default，并保持握手选择逻辑不变，verify why.md#requirement-启动会话级解析一次并校验-scenario-建立连接后进入握手与命令处理
- [√] 2.2 修改 `core/src/main/java/com/javasleuth/command/server/CommandClientHandler.java`：在会话开始创建 `ConfigSnapshot` 并解析强类型配置一次，统一 `maxLineBytes/maxPayload/securityMode/authorization` 等读取，verify why.md#requirement-配置读取强类型化与默认值收敛-scenario-协议相关配置读取（server-与-launcher-一致），depends on task 2.1

## 3. launcher/client（连接参数一致性）
- [√] 3.1 修改 `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`：使用与 server 相同的强类型解析与默认计算（尤其是 `textMaxLineBytes`），避免散落 `getInt(key, default)`，verify why.md#requirement-配置读取强类型化与默认值收敛-scenario-协议相关配置读取（server-与-launcher-一致），depends on task 1.2
- [-] 3.2 修改 `launcher/src/main/java/com/javasleuth/launcher/attach/AgentAttacher.java`（如涉及 protocol/security 参数透传）：改为使用强类型配置对象输出/传递必要参数，verify why.md#requirement-启动会话级解析一次并校验-scenario-建立连接后进入握手与命令处理
  > Note: attach 链路当前通过 `ProductionConfig` 的集中 getter 组装 agentArgs（未涉及 `protocol.text.max.line.bytes` 等派生上限），本次优先收敛“server/launcher 连接协议参数”与默认一致性；为降低侵入与风险，暂不改动 attach 相关代码。

## 4. 默认值一致性与回归测试
- [√] 4.1 新增单测：强类型解析默认值/覆盖优先级/非法值校验（建议新增 `core/src/test/java/com/javasleuth/config/SleuthConfigParserTest.java`），verify why.md#requirement-默认值一致性可自动验证-scenario-ci-运行一致性测试，depends on task 1.2
- [√] 4.2 新增一致性单测：校验 `foundation/src/main/resources/sleuth-default.properties` 包含强类型模型所需 key，且关键默认值与 `SleuthDefaults`/解析规则一致（避免漂移），verify why.md#requirement-默认值一致性可自动验证-scenario-ci-运行一致性测试，depends on task 1.3
- [√] 4.3 新增协议一致性测试：确保 server/launcher 计算出的 `textMaxLineBytes` 默认值一致（含 `frameMaxPayload` 变化场景），verify why.md#requirement-配置读取强类型化与默认值收敛-scenario-协议相关配置读取（server-与-launcher-一致）

## 5. Security Check
- [√] 5.1 执行安全检查（G9）：确认强类型解析不会泄露 secret/password；确认非法配置 fail-fast 路径不会把敏感值写入日志；确认默认值对齐不引入意外放开，verify why.md#risk-assessment

## 6. Documentation Update
- [√] 6.1 更新 `helloagents/wiki/modules/config.md`：补充“强类型配置模型/解析边界/默认值一致性测试”规范与迁移约束（禁止新增散落 key），verify why.md#change-content

## 7. Testing
- [√] 7.1 运行 `mvn test`（根目录）并修复失败用例；确保核心握手/连接/配置相关用例全部通过，verify why.md#requirement-默认值一致性可自动验证-scenario-ci-运行一致性测试
