# Change History Index

This file records index of all completed changes for traceability and query.

---

## Index

| Timestamp | Feature Name | Type | Status | Solution Package Path |
|-----------|--------------|------|--------|----------------------|
| 202601281207 | sleuth_plugin_stream | Refactor | ✅Completed | history/2026-01/202601281207_sleuth_plugin_stream/ |
| 202601281301 | sleuth_handshake_secure_frames | Feature | ✅Completed | history/2026-01/202601281301_sleuth_handshake_secure_frames/ |
| 202601281538 | root_cleanup_docs_scripts | Refactor | ✅Completed | history/2026-01/202601281538_root_cleanup_docs_scripts/ |
| 202601291031 | fix-5-issues | Refactor | ✅Completed | history/2026-01/202601291031_fix-5-issues/ |
| 202601291255 | arthas_core_simplified | Feature | ✅Completed | history/2026-01/202601291255_arthas_core_simplified/ |
| 202601291520 | arthas_stack_tt_replay_simplified | Feature | ✅Completed | history/2026-01/202601291520_arthas_stack_tt_replay_simplified/ |
| 202602011222 | sleuth_hardening_bootstrap | Refactor | ✅Completed | history/2026-02/202602011222_sleuth_hardening_bootstrap/ |
| 202602011706 | core_fixes_java8_jad_session_regex_trace | Refactor | ✅Completed | history/2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/ |
| 202602021233 | quality_audit_more_issues | Refactor | ✅Completed | history/2026-02/202602021233_quality_audit_more_issues/ |
| 202602022232 | security_auth_protocol_trace_refactor | Refactor | ✅Completed | history/2026-02/202602022232_security_auth_protocol_trace_refactor/ |
| 202602031122 | event_driven_upgrade | Refactor | ✅Completed | history/2026-02/202602031122_event_driven_upgrade/ |
| 202602041158 | unified_exec_pipeline | Refactor | ✅Completed | history/2026-02/202602041158_unified_exec_pipeline/ |
| 202602041830 | docs_zh | Refactor | ✅Completed | history/2026-02/202602041830_docs_zh/ |
| 202602042137 | docs_tutorial | Refactor | ✅Completed | history/2026-02/202602042137_docs_tutorial/ |
| 202602042207 | docs_tutorial_command_flow | Refactor | ✅Completed | history/2026-02/202602042207_docs_tutorial_command_flow/ |
| 202602042257 | vmtool_instance_diagnostics | Feature | ✅Completed | history/2026-02/202602042257_vmtool_instance_diagnostics/ |
| 202602051031 | command_pipeline_step_chain | Refactor | ✅Completed | history/2026-02/202602051031_command_pipeline_step_chain/ |
| 202602051334 | giant_files_split_handlers_stack_tt | Refactor | ✅Completed | history/2026-02/202602051334_giant_files_split_handlers_stack_tt/ |
| 202602051436 | command_args_validation_logging | Refactor | ✅Completed | history/2026-02/202602051436_command_args_validation_logging/ |
| 202602051743 | exception_handling_logging | Refactor | ✅Completed | history/2026-02/202602051743_exception_handling_logging/ |
| 202602061101 | move_demo_apps_to_examples | Refactor | ✅Completed | history/2026-02/202602061101_move_demo_apps_to_examples/ |

---

## Archive by Month

### 2026-01

- 202601281207_sleuth_plugin_stream (2026-01/202601281207_sleuth_plugin_stream/) - 插件化命令与流式诊断
- 202601281301_sleuth_handshake_secure_frames (2026-01/202601281301_sleuth_handshake_secure_frames/) - 握手协商 + 严格帧协议 + 插件授权治理
- 202601281538_root_cleanup_docs_scripts (2026-01/202601281538_root_cleanup_docs_scripts/) - 根目录文档/脚本整理（docs/ + scripts/）
- 202601291031_fix-5-issues (2026-01/202601291031_fix-5-issues/) - 协议/安全/插桩重构（5 个问题修复 + 资源治理）
- 202601291255_arthas_core_simplified (2026-01/202601291255_arthas_core_simplified/) - Arthas-like 核心功能简化实现（watch/trace/monitor/tt/jobs/reset 等）
- 202601291520_arthas_stack_tt_replay_simplified (2026-01/202601291520_arthas_stack_tt_replay_simplified/) - Stack 方法调用栈追踪 + TT replay-lite 模板生成

### 2026-02

- 202602011222_sleuth_hardening_bootstrap (2026-02/202602011222_sleuth_hardening_bootstrap/) - 启动/安全/插件/Trace 综合加固（Hardening & Bootstrap）
- 202602011706_core_fixes_java8_jad_session_regex_trace (2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/) - Java 8 兼容 + jad/session/regex/trace/watch/tt 稳定性与安全加固
- 202602021233_quality_audit_more_issues (2026-02/202602021233_quality_audit_more_issues/) - 配置/打包/危险命令分级与关键边界测试补齐
- 202602022232_security_auth_protocol_trace_refactor (2026-02/202602022232_security_auth_protocol_trace_refactor/) - 默认 HMAC 安全姿态 / 授权 SSOT / 危险命令二次确认 / 断连回收
- 202602031122_event_driven_upgrade (2026-02/202602031122_event_driven_upgrade/) - 并发背压/缓存语义统一/高影响命令治理/HMAC 启动自洽（Netty 双栈延期）
- 202602041158_unified_exec_pipeline (2026-02/202602041158_unified_exec_pipeline/) - 多 ClassLoader 选类回滚一致性、插桩失败可恢复、流式命令纳入 Pipeline 与 jobs 并发硬上限
- 202602041830_docs_zh (2026-02/202602041830_docs_zh/) - docs/ 文档中文化（统一为简体中文）
- 202602042137_docs_tutorial (2026-02/202602042137_docs_tutorial/) - 新增 docs/tutorial 教学目录，整理 Attach/Instrumentation 基础知识
- 202602042207_docs_tutorial_command_flow (2026-02/202602042207_docs_tutorial_command_flow/) - 教学文档补充：命令触发插桩与回滚链路（watch/trace/reset/stop）
- 202602042257_vmtool_instance_diagnostics (2026-02/202602042257_vmtool_instance_diagnostics/) - vmtool（lite）：实例追踪/检视/受控调用
- 202602051031_command_pipeline_step_chain (2026-02/202602051031_command_pipeline_step_chain/) - 命令执行链显式化（Step/Interceptor）+ CommandProcessor 拆分（降巨型类耦合）
- 202602051334_giant_files_split_handlers_stack_tt (2026-02/202602051334_giant_files_split_handlers_stack_tt/) - 继续压小巨型文件：协议 handler 拆分 + Stack/TT 子模块化
- 202602051436_command_args_validation_logging (2026-02/202602051436_command_args_validation_logging/) - 参数解析/异常处理/Locale 归一化加固
- 202602051743_exception_handling_logging (2026-02/202602051743_exception_handling_logging/) - 异常处理与输出/日志策略统一（errorId + 最小披露）
- 202602061101_move_demo_apps_to_examples (2026-02/202602061101_move_demo_apps_to_examples/) - 示例/测试应用迁移到 examples，发布产物边界收敛
