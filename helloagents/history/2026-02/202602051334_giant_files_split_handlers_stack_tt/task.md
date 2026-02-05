## Task

- [√] 1. 拆分 `CommandClientHandler` 为 text/framed/binary handlers
- [√] 1.1 抽取握手协商模块（HELLO/CONFIG/UPGRADE）
- [√] 1.2 抽取共享命令执行流程（verify/parse/precheck/execute/stream）
- [√] 1.3 引入 text/framed/binary 三类 handler 并保持行为兼容
- [√] 1.4 补充/调整单测覆盖协议分流与回写语义（必要时）

- [√] 2. StackCommand 子模块化（解析/会话/执行/格式化）
- [√] 2.1 抽取 trace(lite) 解析与执行模块
- [√] 2.2 抽取 session 管理与格式化组件
- [√] 2.3 保持 legacy stack 子命令行为不变

- [√] 3. TtCommand 子模块化（解析/会话/执行/格式化）
- [√] 3.1 抽取 replay 模板生成器
- [√] 3.2 抽取 record/list/detail/stop 的公共组件（最小化重复）

- [√] 4. 测试与知识库同步
- [√] 4.1 运行 `mvn test` 并修复编译/测试问题
- [√] 4.2 更新 `helloagents/wiki/modules/command.md` / `helloagents/CHANGELOG.md`
- [√] 4.3 迁移方案包到 `helloagents/history/2026-02/`，更新 `helloagents/history/index.md`
