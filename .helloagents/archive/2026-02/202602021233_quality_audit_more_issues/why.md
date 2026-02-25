## 背景与目标

本项目是一个可 attach 的 JVM 诊断工具（类似 Arthas 的命令体系），在目标 JVM 内启动命令服务端并通过 CLI 进行交互式诊断与热更新。近期已完成一轮核心修复（Java 8 兼容性、jad/session/cache、regex/wildcard、trace/watch 语义等），下一步需要进一步提升“可用性 + 安全性 + 可维护性”。

本次新增 solution 关注“更多问题的系统性发现与落地修复”，主要聚焦：

1. **默认配置与实现的一致性**：避免出现“配置文件存在但代码不识别 / 代码有默认值但配置文件缺失”的漂移，降低运维误用风险。
2. **安全策略的表达与可审计性**：确保危险操作（如热更新/堆转储/运行时配置变更）在权限系统中被正确标注，便于审计、限流与误操作防护。
3. **打包与启动体验**：减少对脚本的强依赖，让产物可直接 `java -jar` 运行（在保持 Agent 能力的前提下）。
4. **关键边界条件的测试覆盖**：把高风险/高回归概率的边界（绑定地址、security.mode、自举流程、协议上限等）固化为单测，减少回归。
5. **命令 UX 的“未完成痕迹”清理**：例如 `tt` 输出中存在明显 TODO 占位，容易误导用户对功能完成度的判断。

## 已发现的代表性问题（用于驱动任务拆解）

- 配置漂移：
  - `src/main/resources/sleuth-default.properties` 中包含 `production.*` 配置项，但 `ProductionConfig` 的默认值/解析路径并未覆盖这些 key，容易造成“以为生效但实际无效”。
  - `ProductionConfig` 中存在默认配置项（如 `jobs.*`、`performance.command.timeout.max`）但默认配置文件未显式列出，运维可见性不足。
- 产物可执行性不足：
  - `target/java-sleuth-*-jar-with-dependencies.jar` 的 Manifest 目前包含 Agent 相关属性，但缺少 `Main-Class`，无法直接 `java -jar` 启动 Launcher（仍需依赖 `sleuth.sh/.bat`）。
- 风险分级表达不足：
  - 目前内置命令的 `dangerous` 标记较少（例如仅 `stop/reset` 明确标注），与真实风险面（heapdump/redefine/mc/config set 等）存在不一致，影响 `perm`/审计/限流等能力的准确性。
- 关键安全边界缺乏单测固化：
  - “非回环绑定 + security.mode=off 拒绝启动”、“security.mode=hmac 但 secret 为空拒绝启动”等关键逻辑缺少直接测试覆盖，容易在重构时回归。
- `tt` 的 replay 模板输出存在 TODO 占位：
  - 输出中出现 “TODO: obtain instance” 等未完成提示，属于可见的产品瑕疵，需要改为明确的限制说明或提供更可执行的模板。

## 成功标准（验收口径）

- 默认配置与代码默认值一致：无“配置文件存在但代码不识别”的 key；新增/缺失项有明确取舍与文档说明。
- 危险操作在权限系统中可识别：`perm` 等输出能正确体现危险/审计/限流属性；关键危险命令具备合理的默认策略。
- 产物可 `java -jar` 直接启动（至少 fat-jar 具备 `Main-Class`），且不破坏 attach/agent 能力。
- 增补单测覆盖关键边界：新增测试在 `mvn test/verify` 下稳定通过并能防回归。

