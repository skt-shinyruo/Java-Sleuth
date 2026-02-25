## 任务清单（quality_audit_more_issues）

> 说明：本 solution 以“补齐正确性/安全边界/可运维性/可测试性”为主，避免大规模架构重构。

- [√] 1. 配置一致性治理：对齐默认配置与 `ProductionConfig#setDefaults()`（移除/实现 `production.*`，补齐缺失默认项，更新相关文档）
- [√] 2. 打包改进：fat-jar Manifest 增加 `Main-Class`，支持 `java -jar` 直接启动 Launcher（保持 Agent Manifest 不变）
- [√] 3. 安全风险分级：审计并补齐危险命令标记（heapdump/redefine/retransform/mc/config set 等），确保权限系统/`perm` 输出一致
- [√] 4. 审计/限流策略：为高风险命令补齐默认审计与限流（必要时按角色区分），并增加单测固化
- [√] 5. 安全边界单测：覆盖“非回环绑定 + security.mode=off 拒绝启动”、“hmac secret 为空拒绝启动”等关键路径
- [√] 6. 协议上限单测：补齐 `protocol.text.max.line.bytes` 与 frame payload 上限相关的行为测试（含异常/报错路径）
- [√] 7. `tt` UX 修复：移除 replay 模板中的 TODO 占位，改为清晰限制说明 + 更可复制的模板输出，并增加单测
- [√] 8. `profiler` 文案/行为校准：避免误导 “async-profiler” 依赖关系；必要时补齐帮助信息与测试
- [√] 9. 文档一致性修复：清理 docs 中的绝对路径/过期描述，确保与当前代码路径与注册机制一致

## 执行结果

- [√] 执行完成：9/9
- [√] 验证：`mvn test` 通过
