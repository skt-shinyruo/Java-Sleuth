# 命令触发插桩与回滚（watch/trace/reset/stop）

本文解释 Java-Sleuth 中与“插桩生效/撤销”最相关的几条命令链路，帮助你理解：

- 为什么执行 `watch` / `trace` 后，类会立刻被增强（而不是等下次重启或重新加载）。
- 为什么 `watch` / `trace` 结束时会尝试“回滚”字节码。
- `reset` 与 `stop` 分别会清理哪些状态，以及它们的适用场景。

> 提示：如果你还不熟悉 Attach/Instrumentation/Transformer 的基本概念，请先阅读 `docs/tutorial/attach-and-instrumentation.md`。

---

## 1. 总览：谁触发了“真正的类修改”？

在 Java-Sleuth 里，“真正的类字节码修改”发生在目标 JVM 的 transformer 回调 `transform(...)` 被触发并返回了非空字节码时。

`watch/trace` 之所以能“立即生效”，核心是它们在创建增强器（enhancer）之后，会主动调用：

- `Instrumentation#retransformClasses(targetClass)`

这会让**已加载**的 `targetClass` 重新进入 retransformation 流程，从而触发 transformer 的 `transform(...)`，把增强逻辑应用到当前运行中的类上。

---

## 2. `watch`：从命令到插桩生效，再到回滚

`watch` 的链路可以理解为 3 个阶段：选类 → 建会话并插桩 → 退出时清理/回滚。

### 2.1 选类：为什么需要 ClassLoader 维度？

`watch <class-pattern> <method-pattern>` 并不是“从磁盘找 class 文件”，而是从**当前 JVM 已加载类**里挑选目标类。

在多 ClassLoader 场景下，可能存在“同名类不同实现”。因此 Java-Sleuth 的增强器注册以“类名 + loaderId”作为关键维度，避免互相污染。

当候选类不唯一时，建议使用命令参数指定 loader（例如 `--loader <loaderId>`），精确选类（以运行时 `help` 为准）。

### 2.2 插桩生效：为什么一定会调用 retransform？

`watch` 创建会话并触发增强时，通常会按以下顺序执行（概念化步骤）：

1. 生成 `watchId`，创建结果队列（用于把事件从目标 JVM 内部传回命令输出）。
2. 向 `WatchInterceptor` 注册该 `watchId` 的会话与队列（用于接收增强后产生的事件）。
3. 把 `WatchEnhancer` 注册到 transformer（相当于告诉 transformer：这个类需要增强哪些方法）。
4. 调用 `retransformClasses(targetClass)`：让已加载类“立刻套用”新的 transformer 增强逻辑。

从这里开始，只要目标方法被调用，就会产生事件（方法进入/退出/异常等），并被推送到 `watch` 的结果队列中，最终在 `sleuth>` 里输出。

### 2.3 回滚：为什么 stopWatch 要再 retransform 一次？

`watch` 结束时（超时/达到次数/用户中断等），会执行清理动作（概念化步骤）：

1. 从 transformer 移除该会话对应的 enhancer。
2. 再次 `retransformClasses(targetClass)`：让“去除增强器后的 transformer 逻辑”重新应用到类上，尽力恢复原始字节码状态（best-effort）。
3. 从 `WatchInterceptor` 注销 `watchId` 会话（释放队列/防止继续发布事件）。

同时，Java-Sleuth 会把清理动作绑定到客户端连接的生命周期：如果连接断开，也会触发回收，避免增强长期残留。

---

## 3. `trace`：链路与 `watch` 相同，但结果聚合不同

`trace` 的“插桩生效/回滚”链路与 `watch` 基本一致：

- 同样：注册 interceptor 会话 → 注册 enhancer → `retransformClasses(...)` 触发 transform → 产生事件 → 输出。
- 同样：结束时移除 enhancer 并 retransform 回滚。

差异主要在**事件语义与输出方式**：

- `watch` 更偏“事件流”：你会看到每次进入/退出/异常的条目（可选捕获 params/return/exception/cost 等）。
- `trace` 更偏“单次调用聚合”：通常会把一整次调用的子调用树组织起来再输出，并可以通过深度、次数、超时、采样率等控制开销。

---

## 4. `reset`：全局回收（会影响所有插桩会话）

`reset` 的设计目标是：**清空当前 JVM 内所有 active 的插桩会话与相关状态，并尽力恢复字节码**。

典型行为包括（以实现为准，概念化总结）：

- 停止后台 jobs（避免还有任务持续消费/持续产生事件）。
- 清空各类 interceptor 会话（watch/trace/monitor/tt/stack 等）。
- 从 transformer 移除全部 enhancers。
- 对“曾经被增强过的类”执行 retransform（best-effort）：希望恢复到“没有 enhancer 生效时”的字节码状态。

适用场景：

- 插桩会话太多、想一键清理。
- 误选类/误配条件导致输出异常或开销过大。
- 排障结束，需要尽量把目标 JVM 恢复到干净状态。

---

## 5. `stop`：停止 agent（关闭命令服务与 transformer）

`stop` 的设计目标是：让目标 JVM 内的 Java-Sleuth **整体退出**（best-effort），包括：

- 关闭命令处理线程/服务（Launcher 会话会断开）。
- 停止后台 jobs 并注销所有 interceptor 会话。
- 移除 transformer 上的 enhancers，并对相关类做 retransform 尝试恢复字节码。
- 从 JVM `Instrumentation` 中移除 transformer。
- 清理 agent 的“已 attach 标记”，以便后续可以再次 attach（如果需要）。

适用场景：

- 诊断完成，明确不再需要 Java-Sleuth 常驻。
- 端口/协议/安全策略需要重新初始化，希望“stop 后重新 attach”走全量初始化。

---

## 6. 重要边界与常见误区（建议必读）

1. **retransform 是 best-effort，不保证 100% 可逆**  
   部分 JVM/类可能不可修改（`isModifiableClass=false`），或 retransformation 受限；此外，如果目标 JVM 同时存在其他 transformers，retransform 时也会一起参与，结果可能受其影响。

2. **“注册 transformer”≠“立刻改所有类”**  
   没有对应 enhancer 的类，transformer 会选择不改（返回 `null`），因此 attach 后不会“全量插桩”，而是命令按需启用。

3. **多 ClassLoader 一定要重视选类**  
   同名类在不同 loader 下可能行为完全不同；优先使用 `--loader` 精确选类，避免“看起来插桩成功但业务并没有触发”的错觉。

4. **watch/trace 的开销是可控的**  
   优先用 `--count/--timeout/--depth/--sample` 等限制范围，排障结束后及时退出会话；必要时直接 `reset` 清理。

