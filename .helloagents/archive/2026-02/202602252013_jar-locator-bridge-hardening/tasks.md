# 任务清单: jar-locator-bridge-hardening

> **@status:** completed | 2026-02-25 20:39

```yaml
@feature: jar-locator-bridge-hardening
@created: 2026-02-25
@status: completed
@mode: R2
```

<!-- LIVE_STATUS_BEGIN -->
状态: completed | 进度: 7/7 (100%) | 更新: 2026-02-25 20:36:54
当前: 全部任务完成（Jar 定位确定性增强 + 可控 CWD 扫描）
<!-- LIVE_STATUS_END -->

## 进度概览

| 完成 | 失败 | 跳过 | 总数 |
|------|------|------|------|
| 7 | 0 | 0 | 7 |

---

## 任务列表

### 1. agent（SleuthAgent）

- [√] 1.1 在 `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java` 中增加 `sleuth.locator.allowCwdScan` 开关，并在 CWD 兜底命中时输出一次性提示
- [√] 1.2 在 `agent/src/main/java/com/javasleuth/agent/SleuthAgent.java` 中将多候选选择从 `lastModified` 优先改为“文件名版本优先”（无法解析版本再回退）
  - 依赖: 1.1

### 2. bootstrap（JarLocator）

- [√] 2.1 在 `bootstrap/src/main/java/com/javasleuth/bootstrap/util/JarLocator.java` 中增加 `sleuth.locator.allowCwdScan` 开关（控制 CWD 相对目录扫描）
- [√] 2.2 在 `bootstrap/src/main/java/com/javasleuth/bootstrap/util/JarLocator.java` 中将多候选选择从 `lastModified` 优先改为“文件名版本优先”（无法解析版本再回退）
  - 依赖: 2.1

### 3. 文档

- [√] 3.1 更新 `docs/usage/getting-started.md`：补齐 bridge/container override 与诊断开关建议（兼容优先，不改变默认）

### 4. 测试

- [√] 4.1 更新 `core/src/test/java/com/javasleuth/util/JarLocatorTest.java`：覆盖“版本优先选择”与 `allowCwdScan=false` 的行为
  - 依赖: 2.2

### 5. 知识库

- [√] 5.1 更新 `.helloagents/CHANGELOG.md` 与必要的模块文档索引（仅记录本次变更）
  - 依赖: 4.1

---

## 执行日志

| 时间 | 任务 | 状态 | 备注 |
|------|------|------|------|
| 2026-02-25 20:36:54 | jar-locator-bridge-hardening | completed | `mvn test` 通过；已同步 docs 与 KB |

---

## 执行备注

- 兼容优先：默认行为尽量不破坏；更严格策略通过开关逐步收敛。
- 子代理 `pkg_keeper` 未参与填充（主代理降级执行），后续如需严格按服务绑定角色执行可补充调用记录。 
