# Technical Design: 启动/安全/插件/Trace 综合加固（Hardening & Bootstrap）

## Technical Solution

### Core Technologies
- Java 8+ / Maven
- Attach API（`com.sun.tools.attach` / `jdk.attach`）
- ASM 字节码增强（现有增强框架复用）
- JLine 交互式 CLI
- Socket 文本/分帧/二进制协议（现有协议框架复用）
- HMAC-SHA256（请求完整性 + 基础防重放；不提供加密）

### Implementation Key Points

#### 1) 启动/发布去硬编码（jar 版本无感）
1. **Launcher 自动定位自身 jar：**
   - 优先使用 `SleuthLauncher.class.getProtectionDomain().getCodeSource().getLocation()` 获取运行载体
   - 若为 jar：该 jar 既作为 Launcher classpath，也作为 agent jar（`VirtualMachine.loadAgent`）
   - 若为目录（IDE 运行）：回退扫描 `target/java-sleuth-*-jar-with-dependencies.jar`（按最新时间或第一个匹配）
   - 允许用户通过系统属性/环境变量覆盖：`sleuth.agent.jar` / `SLEUTH_AGENT_JAR`
2. **脚本稳定化：**
   - `sleuth.sh`/`sleuth.bat` 使用 `SCRIPT_DIR` 作为基准目录，支持从任意 cwd 启动
   - 使用通配符定位 jar（避免版本硬编码）
   - 取消 `grep -P` 版本解析：改为“若存在 `$JAVA_HOME/lib/tools.jar` 则追加 classpath”（JDK8）
3. **示例脚本同步：**
   - `scripts/demo/*`、`scripts/test/*` 等对 jar 名称的硬编码改为通配符/变量化

#### 2) 安全自举（HMAC 默认更安全 + 免静态口令）
1. **HMAC secret 自举：**
   - attach 时 Launcher 生成随机 secret（Base64URL），通过 agent args 注入目标 JVM：
     - `security.mode=hmac`
     - `security.hmac.secret=<generated>`
   - Launcher 自身同步写入 runtime config（`ProductionConfig.setRuntimeConfig`）以保证 `RequestSecurityManager.signCommand(...)` 生效（否则会出现“Agent 要求签名但 Launcher 仍发明文”的不一致）
   - 若用户已在配置文件/环境变量中提供 secret，则不覆盖；仅在“缺省/空值”时生成
2. **认证与授权策略（避免硬编码口令）：**
   - 将 `AuthenticationManager` 的默认口令移除，改为读取配置/环境变量，例如：
     - `security.auth.admin.password` / `SLEUTH_AUTH_ADMIN_PASSWORD`
     - `security.auth.operator.password` / `SLEUTH_AUTH_OPERATOR_PASSWORD`
     - `security.auth.viewer.password` / `SLEUTH_AUTH_VIEWER_PASSWORD`（可选）
   - 增加开关控制：
     - `security.auth.password.enabled`（默认 false）
     - `security.auth.allow.insecure_defaults`（默认 false，仅开发环境允许）
   - 推荐路径：当 `security.mode=hmac` 启用时，允许配置 `security.hmac.session.role=ADMIN|OPERATOR|VIEWER`（默认 OPERATOR 或 ADMIN，取决于风险偏好），从而实现“仅凭 HMAC secret 即可具备对应权限”，不再要求传输用户名口令
3. **兼容策略：**
   - 对于 legacy/off（loopback）场景：保留 `auth` 命令与 RBAC 机制
   - 对于非回环 bind：维持现有“security.mode=off 拒绝启动”的保护，同时推荐 hmac（并在文档中声明其不提供加密，必要时配合隧道/本机转发）

#### 3) 插件加载加固（供应链控制 + classloader 生命周期）
1. **显式启用：**
   - 新增 `plugins.enabled`（默认 false）
   - 当 `plugins.enabled=false` 时 `CommandRegistry` 不扫描/加载插件目录
2. **allowlist + 可选 sha256：**
   - 新增配置项：
     - `plugins.allowlist`：允许的 jar 文件名列表（逗号分隔）
     - `plugins.verify.sha256`：是否启用 sha256 校验（默认 true 或 false，需权衡）
     - `plugins.sha256.<jarName>=<hex>`：每个 jar 的 sha256（启用校验时必填）
   - 校验不通过：跳过加载并通过 `AuditLogger.logSecurityViolation(...)` 记录
3. **Windows 友好加载策略（可选）：**
   - staging copy：将 jar 复制到 `plugins/.sleuth-cache/`（带时间戳/sha256 前缀），再从缓存路径加载，降低原 jar 被锁定的影响
4. **资源释放：**
   - `CommandRegistry` 保存插件 `URLClassLoader` 引用，并在 Agent shutdown（`SleuthAgent.shutdown()`）或 CommandProcessor 关闭时显式 `close()`

#### 4) Trace 采样一致性（保证 entry/exit 配对）
1. **采样从“事件级”改为“调用级”：**
   - 在 `onMethodEntry` 决定本次调用是否采样，并将决定 push 到 ThreadLocal 栈（与 depth 对齐）
   - `onMethodExit` pop 决定并仅在采样为 true 时输出 exit/exception 事件
   - `onSubMethodCall` 使用 ThreadLocal 栈 peek 决定，确保链路一致
2. **默认采样率更保守：**
   - 调整 `monitoring.trace.sample.rate` 默认值（例如 0.1~0.2），并在 `trace` 命令提供显式覆盖参数（如 `--sample=0.5`）
   - 保持指标统计（published/dropped/evicted/sampledOut）可用于自诊断

#### 5) 日志/审计输出副作用治理
1. **统一错误输出：**
   - 将 `printStackTrace()` 与 `System.out/err` 的异常栈输出，替换为 `SleuthLogger.error(..., e)` 并受 `logging.level` 控制
2. **审计日志可配置：**
   - 新增配置项：
     - `logging.audit.file` / `logging.security.file`（默认相对路径，允许绝对路径）
     - `logging.audit.console`（默认 false；仅在 DEBUG/TRACE 或显式启用时输出到控制台）
   - 文件不可写时降级策略：仅写控制台（或仅写内存计数），并记录一次 WARN

## Architecture Decision ADR

### ADR-1: 使用“Launcher 自举 HMAC”作为默认安全路径
**Context:** 现有默认 `security.mode=off` 且 secret 为空，易误用；同时 Launcher 已具备对命令签名的能力，但需要与 Agent 侧配置一致。  
**Decision:** Launcher attach 时在缺省情况下自动生成 secret，并同时注入 Agent 与 Launcher 的运行时配置，使交互体验不变但默认更安全。  
**Rationale:** 不引入 TLS/证书管理成本；将“安全正确性”从用户手工配置转为工具自动完成；保留 off/legacy 兼容通道。  
**Alternatives:**  
- TLS（未实现）→ Rejection reason: 证书管理复杂且不在当前项目范围  
- 仍默认 off → Rejection reason: 易误用且与生产化目标冲突  
**Impact:** 第三方客户端需要实现签名协议才能使用；推荐使用官方 Launcher。

### ADR-2: 插件默认关闭 + allowlist/sha256 校验
**Context:** 插件目录默认扫描会带来供应链与运维风险，且 classloader 资源释放不明确。  
**Decision:** 新增 `plugins.enabled=false` 默认值，只有显式开启才加载；提供 allowlist 与可选 sha256 校验；并在 shutdown 时释放 classloader。  
**Rationale:** 最小化默认攻击面；将风险留给明确选择插件能力的用户。  
**Alternatives:**  
- 保持默认开启 → Rejection reason: 默认攻击面过大  
- 仅 allowlist 不校验 sha256 → Rejection reason: 防篡改能力不足（但可作为低成本选项保留）  
**Impact:** 插件用户需要补充配置；文档需提供模板与迁移说明。

## Security and Performance
- **Security:**
  - HMAC 签名 + nonce 防重放作为默认推荐路径（注意：不加密）
  - 取消硬编码口令，凭配置/环境变量或 HMAC 授权
  - 插件加载需显式启用并支持 allowlist/sha256 校验
  - 审计日志对敏感值脱敏，避免泄露 secret/token
- **Performance:**
  - 默认 trace 采样更保守，降低误用影响
  - Trace 采样一致性避免“半链路”导致的额外解析成本与误判
  - 插件加载使用一次性扫描，运行期不引入额外开销

## Testing and Deployment
- **Testing:**
  - 新增/调整单测：
    - jar 定位逻辑（jar/目录两种运行形态）
    - trace 采样配对一致性（entry/exit/subcall）
    - 插件 allowlist/sha256 校验与 classloader close（必要时用临时目录）
    - 认证改造后的行为（无默认口令、显式配置才可登录）
  - 运行 `mvn test` 回归现有测试
- **Deployment:**
  - 更新 `README.md`、`docs/usage/commands.md`、`docs/ops/production-deployment-guide.md`
  - 更新内部知识库（`helloagents/wiki/modules/*`），确保与代码一致
  - 提供 `config-templates/production-sleuth.properties` 的安全默认示例（含插件/审计路径/采样率建议）
