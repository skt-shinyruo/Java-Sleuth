# Task List: 去除 legacy 兼容（v5）

Directory: `helloagents/plan/202602201447_remove_legacy_compat_v5/`

---

## 1. 删除 legacy 构造器（强制注入）
- [√] 1.1 移除 `WatchCommand/TraceCommand/TtCommand/MonitorCommand/StackCommand/JobsCommand` 的 legacy 构造器
- [√] 1.2 移除 `ResetCommand/VmToolCommand` 的 legacy 构造器
- [√] 1.3 移除 `HealthCommand/StatusCommand` 的 legacy 构造器
- [√] 1.4 移除 `AuthCommand/SessionCommand` 的 legacy 构造器

## 2. 更新调用点与测试
- [√] 2.1 更新 core 测试中直接 new 命令对象的调用点（补齐注入参数）
- [√] 2.2 全量搜索并清理旧构造器引用

## 3. 文档与验证
- [√] 3.1 更新知识库（移除 legacy/bridge-only 描述）
- [√] 3.2 更新 CHANGELOG（breaking change 说明）
- [√] 3.3 运行 `mvn test`
