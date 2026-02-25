## Task

- [√] 1. 扩展 `CommandArgs`：统一 int/long 解析、默认值、范围校验、错误码
- [√] 2. StackLegacyOperations：替换直接 parseInt/parseLong，补齐范围与错误提示；修复 Locale
- [√] 3. CommandProcessor：替换吞异常为 DEBUG/WARN 日志（保持 best-effort 语义）
- [√] 4. CommandClientHandler/CommandRequestExecutor/CommandPipeline：修复 Locale.ROOT 大小写归一化关键点
- [√] 5. 测试：新增 `CommandArgs` 单测；运行 `mvn test`
- [√] 6. 知识库同步：更新 `helloagents/wiki/modules/command.md`、`helloagents/CHANGELOG.md`；迁移方案包并更新 `helloagents/history/index.md`
