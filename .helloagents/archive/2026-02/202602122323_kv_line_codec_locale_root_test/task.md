# Task List: KvLineCodec Locale.ROOT + 最小单测

- [√] 1. `KvLineCodec`：key 归一化改为 `toLowerCase(Locale.ROOT)`，避免默认 Locale 影响
- [√] 2. 新增单测：覆盖空输入/非法 token/忽略 verb/Locale 独立性
- [√] 3. KB 同步：补充 `KvLineCodec` 的 Locale 约束与测试说明（如需要）
- [√] 4. 验证：`mvn test` + `mvn -DskipTests package`
- [√] 5. 迁移方案包到 `helloagents/history/` 并更新索引
