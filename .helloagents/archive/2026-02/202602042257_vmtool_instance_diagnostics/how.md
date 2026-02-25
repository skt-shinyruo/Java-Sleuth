# how

## 总体方案

### 1) 构造器插桩追踪实例（track）
- 对目标类（可选包含子类）的 `<init>` 构造器做 ASM 增强
- 在构造器 RETURN 时回调 `VmToolInterceptor.onConstructed(trackId, this)`
- 通过识别构造器首个 `invokespecial <init>` 的 owner，跳过 `this(...)` 构造链，避免重复上报

### 2) 有界弱引用缓存（防泄漏）
- `VmToolInterceptor` 以 `trackId` 维度维护 TrackSession
- 每次上报生成递增 `refId`，保存为 `WeakReference<Object>`
- 缓存使用有界 ring buffer + map 对齐，确保最多保留 N 条实例引用

### 3) 对象检视与条件过滤
- `SleuthObjectInspector` 仅通过反射读取字段（不调用业务方法），字段名敏感则脱敏
- `VmToolObjectConditionEvaluator` 提供受控的 `--where lhs:op:rhs` 过滤：
  - 元数据：class/id/thread/ageMs
  - 字段：field.xxx

### 4) 受控方法调用（invoke）
- `VmToolMethodInvoker` 只支持基础类型/包装类型/boolean/String/enum/null 参数
- 默认拒绝对高风险 JDK 类/方法调用（可通过 `--unsafe` 显式放开）
- 子命令级二次确认：`invoke` / `invoke-static` 调用 `DangerousCommandConfirmationManager`
- RBAC：CommandMeta 将 `invoke` / `invoke-static` 子命令升级为 ADMIN

### 5) 回滚与 reset 联动
- `VmToolSessionRegistry` 记录每个被增强类对应的 enhancer，stop 时仅移除自身 enhancer 并 retransform
- reset 时调用 `VmToolSessionRegistry.stopAll(..., reason=reset)` 清理会话与增强

