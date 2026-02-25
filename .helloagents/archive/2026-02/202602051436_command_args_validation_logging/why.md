## Why

当前命令子系统在“坏输入/异常”路径上仍不够鲁棒，主要体现在：

1. **参数解析缺少统一的 try/catch + 范围校验**：部分命令直接 `parseInt/parseLong`，遇到非法输入会抛出 `NumberFormatException`/`IllegalArgumentException`，最终表现为命令报错信息不友好、甚至在部分边界下中断连接/流程。
2. **吞异常导致黑洞**：例如启动/运行期的 best-effort 配置与状态探测使用 `catch (Exception ignore)`，把真实故障变成静默失败，影响排障与稳定性。
3. **Locale 相关大小写归一化隐患**：命令名/握手关键字使用 `toLowerCase()/toUpperCase()` 未显式 `Locale.ROOT`，在土耳其语等 Locale 下可能导致命令无法识别或行为异常。

本次目标：

- 抽象一个统一的 args/options 数值解析与范围校验工具层（支持错误码、默认值、范围限制），让命令实现只关注业务语义。
- 对“可忽略异常”补齐 DEBUG/WARN 级别日志（默认不打爆日志，debug 可见），避免 silent failure。
- 修复关键链路的 Locale 归一化隐患，保证跨 Locale 行为一致。

