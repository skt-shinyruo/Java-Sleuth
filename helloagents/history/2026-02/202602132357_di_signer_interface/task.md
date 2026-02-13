# Task List: 显式构造注入（Security Managers）+ Launcher Signer 小接口

Directory: `helloagents/plan/202602132357_di_signer_interface/`

---

## 1. foundation（Signer 抽象 + 构造注入）
- [√] 1.1 新增 `CommandSigner` 接口（最小签名能力抽象）
- [√] 1.2 `RequestSecurityManager` 实现 `CommandSigner`，并增加可注入构造函数（保留 `getInstance()`）
- [√] 1.3 `AuthorizationManager` 增加可注入构造函数（保留 `getInstance()`）

## 2. launcher（ProtocolClient 依赖收敛）
- [√] 2.1 `ProtocolClient` 依赖 `CommandSigner` 而非具体 `RequestSecurityManager`，并保留默认 connect 行为与兼容重载

## 3. core（Composition Root 收敛）
- [√] 3.1 在 `SleuthAgentCore` 中用显式构造创建 `RequestSecurityManager`/`AuthorizationManager` 并注入 `CommandProcessor`

## 4. Security Check
- [√] 4.1 复核：无明文 secret/sessionId 泄露；shutdown 仍能清理缓存；符合 G9

## 5. Documentation Update
- [√] 5.1 同步知识库：`helloagents/wiki/modules/security.md`、`helloagents/wiki/modules/launcher.md`、`helloagents/wiki/modules/command.md`、`helloagents/CHANGELOG.md`

## 6. Testing
- [√] 6.1 `mvn test` 通过；必要时新增轻量单测覆盖 signer 注入与显式构造路径
