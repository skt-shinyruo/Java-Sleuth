# Task List: 安全组件依赖注入 + instance shutdown 收敛

## Tasks
- [√] 1. 为 `AuthorizationManager`/`RequestSecurityManager`/`DangerousCommandConfirmationManager` 增加 instance `shutdown()`，并让 `shutdownInstance()` 复用
- [√] 2. `ShutdownCoordinator` 支持注入上述 manager，并优先调用注入实例 shutdown（fallback 到 shutdownInstance）
- [√] 3. `VmToolCommand`/`AuthCommand`/`SessionCommand` 改为注入式依赖（保留默认兼容）
- [√] 4. `CommandRegistry`/`BuiltinCommandProvider`/`CommandProcessor` 装配链路传递依赖
- [√] 5. 运行 `mvn test` 验证
- [√] 6. 同步知识库与 `helloagents/CHANGELOG.md`
- [√] 7. 迁移 solution package 到 history 并提交 git commit
