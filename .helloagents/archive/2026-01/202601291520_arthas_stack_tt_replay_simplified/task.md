# 任务列表: Arthas 核心能力补齐（Stack 追踪 + TT Replay Lite）

Directory: `helloagents/plan/202601291520_arthas_stack_tt_replay_simplified/`

---

## 1. Stack（方法调用栈追踪）
- [√] 1.1 扩展 `stack` 命令参数分支：保留 `stack monitor start|stop|status`，新增 `stack <classPattern> <methodPattern>` 模式（支持 `-n/-t/--depth/--bg`），验证 why.md#requirement-stack（方法调用栈追踪）-scenario-追踪某个方法的调用来源
- [√] 1.2 新增插桩增强器（StackEnhancer）：在目标方法入口触发 StackInterceptor 记录调用栈，验证 why.md#requirement-stack（方法调用栈追踪）-scenario-追踪某个方法的调用来源，依赖 1.1
- [√] 1.3 新增 StackInterceptor + StackTraceResult 数据结构，并把统计信息接入 `status`（活动会话数/丢弃数等），验证 why.md#requirement-stack（方法调用栈追踪）-scenario-追踪某个方法的调用来源，依赖 1.2
- [√] 1.4 扩展 `reset`：支持一键停止/清理 stack 追踪会话与撤销增强，验证 why.md#requirement-stack（方法调用栈追踪）-scenario-追踪某个方法的调用来源，依赖 1.3

## 2. TT Replay Lite（生成复现模板）
- [√] 2.1 为 `tt` 增加 `replay <recordId>` 子命令（lite）：输出方法签名、参数安全摘要、代码模板（不执行），验证 why.md#requirement-tt-replay-lite（生成复现模板）-scenario-基于-tt-记录生成复现代码骨架
- [√] 2.2 增强 replay 输出：对基础类型尽量输出可复现字面量（String/Number/Boolean/null），复杂对象输出受控摘要，验证 why.md#requirement-tt-replay-lite（生成复现模板）-scenario-基于-tt-记录生成复现代码骨架，依赖 2.1

## 3. 插桩健壮性与测试
- [√] 3.1 新增 enhancement 单测：watch/tt 插桩不破坏返回值（对象/基本类型/void）与异常路径，验证 why.md#requirement-插桩语义健壮性（返回值异常不破坏）
- [√] 3.2 新增 stack 逻辑单测：注册/注销、深度限制、输入边界，验证 why.md#requirement-stack（方法调用栈追踪）-scenario-追踪某个方法的调用来源，依赖 1.3

## 4. 安全检查
- [√] 4.1 执行安全检查（输入校验、权限控制、敏感信息处理、性能保护），覆盖 stack/tt replay 新增路径

## 5. 文档更新
- [√] 5.1 更新知识库：`helloagents/wiki/modules/command.md`、`helloagents/wiki/modules/enhancement.md`、`helloagents/wiki/modules/monitor.md`，补充 stack 追踪与 tt replay-lite
- [√] 5.2 更新 `helloagents/CHANGELOG.md`（新增功能 + 兼容性说明）

## 6. 验证与回归
- [√] 6.1 运行 `mvn test` 并确认通过
- [-] 6.2 （可选）手工 attach 验证：启动 demo app，执行 `stack <class> <method>` 与 `tt replay <id>`，检查输出可用性
  > Note: 本轮在单元测试层面完成了字节码校验与语义回归；手工 attach 验证需要结合实际目标 JVM/业务类进行。
