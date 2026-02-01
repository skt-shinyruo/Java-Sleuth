# enhancement

## Purpose
字节码增强与 ASM 插桩实现。

## Module Overview
- **Responsibility:** Transformer 与 Enhancer 实现
- **Status:** ✅Stable
- **Last Updated:** 2026-02-01

## Specifications

### Requirement: 运行期插桩
**Module:** enhancement
支持 watch/trace/monitor 等命令动态插桩。

#### Scenario: 为目标类添加拦截
前置：Command 发起增强  
- 创建 Enhancer
- retransform 目标类

#### Notes
- 方法匹配支持 `*` 通配符（例如 `execute*`），并且会对异常路径做 try/catch 以确保退出事件可被记录（watch/trace/monitor/tt）。
- 新增增强器：MonitorEnhancer、TtEnhancer（TT-lite）、StackEnhancer（方法触发调用栈）。
- 为避免字节码校验失败（VerifyError），异常处理器依赖的本地变量会先初始化；对有返回值的方法会先保存返回值到本地变量，再调用拦截器后回填到原始 return 指令所需的操作数栈。
- TraceEnhancer 对“同一类内可被 trace 的方法调用”跳过 SUB_METHOD_CALL 注入，避免 SUB + 子节点双份记录导致的语义重复。

### Requirement: 多会话增强叠加
**Module:** enhancement
支持同一类多个 Enhancer 叠加与独立移除。

#### Scenario: 并发 watch/trace
前置：多个会话同时启用  
- Enhancer 链式组合
- 停止会话仅移除对应 Enhancer

### Requirement: 代理类覆盖与日志降噪
**Module:** enhancement
默认允许对常见代理类（例如 Spring/CGLIB `$$EnhancerBySpringCGLIB$$`）执行增强，避免 watch/trace 命中率不足；同时将 transform 日志纳入等级控制避免刷屏。

#### Scenario: 代理类 watch/trace 可命中且 INFO 不刷屏
前置：目标类为代理类且命令触发 retransform  
- 不再一刀切过滤所有包含 `$$` 的类名  
- 仍过滤 `$$Lambda$` 等噪音类  
- `logging.level=DEBUG` 时输出增强细节，INFO 默认不逐次打印

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
- monitor
- data

## Change History
- 202601281100_init_kb (planned)
- 202601281207_sleuth_plugin_stream (history/2026-01/202601281207_sleuth_plugin_stream/) - Enhancer 链式叠加
- 202601291031_fix-5-issues (history/2026-01/202601291031_fix-5-issues/) - 代理类过滤策略调整与 transform 日志等级控制
- 202602011706_core_fixes_java8_jad_session_regex_trace (history/2026-02/202602011706_core_fixes_java8_jad_session_regex_trace/) - TraceEnhancer 语义去重与稳定性加固
