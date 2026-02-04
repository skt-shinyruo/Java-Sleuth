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
