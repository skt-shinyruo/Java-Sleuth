# Docker Demo（纯交互）

目标：容器启动后先常驻一个 Demo JVM 进程；你再 `docker exec -it` 进入容器手动运行 `./sleuth.sh`，在 `sleuth>` 中敲命令演示。

说明：本镜像会在构建阶段编译 `examples/` 下的示例应用，并在容器启动时以独立 classpath 运行示例主类（`/opt/java-sleuth/examples-classes`）。因此示例类不会进入发布 jar/fat-jar，产物边界更干净。

> 提示：默认会话角色通常为 `operator`（可运行大多数观测/插桩命令）；若要演示 `config/audit/heapdump/mc/redefine/...` 等管理与高风险命令，推荐用下文的 **admin 会话启动方式**。

## 构建镜像

> Dockerfile 位于 `docker/demo/Dockerfile`，构建上下文使用仓库根目录。

```bash
docker build -t java-sleuth-demo -f docker/demo/Dockerfile .
```

## 运行容器（后台）

```bash
docker run -d --name java-sleuth-demo java-sleuth-demo
```

## 进入容器并启动 Java-Sleuth（交互式）

```bash
docker exec -it java-sleuth-demo ./sleuth.sh
```

进入 `sleuth>` 后，从进程列表里选择 `EnhancedTestApplication` 对应的 PID，然后即可手动演示命令（例如 `dashboard/thread/sc/sm/watch/trace`）。

## （推荐）以 admin 会话启动（用于演示全部命令）

> 该方式只影响启动 Launcher 的配置（通过 `JAVA_TOOL_OPTIONS` 注入 `sleuth.*` system property），便于在 demo 环境中演示 `admin` 权限命令；不建议照搬到生产环境。

```bash
docker exec -it java-sleuth-demo bash -lc 'JAVA_TOOL_OPTIONS="-Dsleuth.security.hmac.session.role=admin" ./sleuth.sh'
```

进入 `sleuth>` 后，可以先执行 `perm` 了解权限模型；如果你需要演示口令认证，再看下方 `auth` 示例。

## Demo 目标类/方法（可直接用于 class/method pattern）

本镜像默认运行的 Demo JVM 主类：

- `com.javasleuth.test.EnhancedTestApplication`
- 内部类：`com.javasleuth.test.EnhancedTestApplication$BusinessLogic`

常用方法（适合 `sm/watch/trace/monitor/stack/tt`）：

- `processBusinessTask`
- `performCalculations`
- `simulateErrorScenarios`
- `calculateFibonacci`
- `getGreeting`
- `processOrder`（在 `$BusinessLogic` 中）

## 命令速查（可直接复制到 `sleuth>`）

> 说明：
> - 下列命令**不包含** `sleuth>` 前缀，复制粘贴即可执行。
> - 对于 `watch/trace`：不要用 `watch --help` / `trace --help`（会被当成 class-pattern 校验失败）；如需用法说明，直接执行 `watch` 或 `trace`（不带参数）即可。
> - 带 `--bg` 的命令会返回 `job-id`，用 `jobs tail/stop` 管理后台任务。

### 0) 基础/会话/权限

```text
help
version
perm
session
quit
```

### 1) JVM / 线程 / 内存 / 系统信息

```text
dashboard
dashboard detailed
jvm

thread
thread -d
thread -b
thread -n 5 -i 1000

memory
memory pools
memory gc
memory heap
memory nonheap
memory direct

sysprop java.version
sysprop java.*
sysenv PATH
sysenv JAVA_*

vmoption
vmoption list *GC* 20
vmoption get PrintGCDetails
```

### 2) 类 / 方法检索（`sc` / `sm`）

```text
sc com.javasleuth.test.EnhancedTestApplication
sc com.javasleuth.test.EnhancedTestApplication$BusinessLogic
sc *EnhancedTestApplication*

sm com.javasleuth.test.EnhancedTestApplication process*
sm com.javasleuth.test.EnhancedTestApplication calculateFibonacci
sm com.javasleuth.test.EnhancedTestApplication getGreeting
```

### 3) 插桩观测：`watch` / `trace` / `monitor`

```text
# watch（实时捕获参数/返回值/异常/耗时）
watch com.javasleuth.test.EnhancedTestApplication processBusinessTask -n 5
watch com.javasleuth.test.EnhancedTestApplication$BusinessLogic processOrder -n 5
watch com.javasleuth.test.EnhancedTestApplication calculateFibonacci -n 3 --expr params,return,cost,thread
watch com.javasleuth.test.EnhancedTestApplication processBusinessTask -n 20 --condition cost:gt:1000000

# trace（调用链与耗时）
trace com.javasleuth.test.EnhancedTestApplication processBusinessTask -n 5 -d 5
trace com.javasleuth.test.EnhancedTestApplication calculateFibonacci -n 5 -d 10 --sample 1.0

# monitor（周期统计）
monitor com.javasleuth.test.EnhancedTestApplication processBusinessTask -i 1000 -n 5
monitor com.javasleuth.test.EnhancedTestApplication processBusinessTask -i 1000 -n 10 --bg
```

后台任务管理（适用于 `watch/trace/monitor/tt/stack` 的 `--bg`）：

```text
jobs
jobs list
jobs tail <job-id> 50
jobs stop <job-id>
```

### 4) 调用栈：`stack`

```text
# 方法触发栈采样（lite）
stack com.javasleuth.test.EnhancedTestApplication processBusinessTask -n 5 --depth 30

# 全量/指定线程 dump
stack dump

# 线程状态/死锁/热点栈
stack blocked
stack deadlock
stack hot 10
stack stats

# 持续监控 + 分析
stack monitor start 500
stack monitor status
stack analyze 20
stack monitor stop
stack clear
```

### 5) TimeTunnel-lite：`tt`

```text
tt record com.javasleuth.test.EnhancedTestApplication processBusinessTask -n 5
tt list
tt detail 1
tt replay 1
tt clear
tt stop <ttId>
```

### 6) ClassLoader / JMX / Logger

```text
classloader list
classloader tree
classloader stats
classloader find *EnhancedTestApplication*
classloader urls

mbean --help
mbean domains
mbean list java.lang:*
mbean info java.lang:type=Runtime
mbean get java.lang:type=Threading ThreadCount
mbean invoke java.lang:type=Memory gc

logger list
logger list com.javasleuth* 50
logger set com.javasleuth DEBUG
```

### 7) 反编译 / 导出 / 静态字段

```text
# jad / dump 属于高影响命令：默认需要二次确认（先拿 token，再追加 --confirm 重试）
jad com.javasleuth.test.EnhancedTestApplication
jad com.javasleuth.test.EnhancedTestApplication --confirm <token>

dump *EnhancedTestApplication* --output /tmp/sleuth-dump --limit 20
dump *EnhancedTestApplication* --output /tmp/sleuth-dump --limit 20 --confirm <token>
getstatic com.javasleuth.test.EnhancedTestApplication * --limit 50 --deep 1
```

### 8) 健康/状态/指标/配置/审计

```text
health
status
metrics summary
metrics detailed
metrics json

# 下面两个命令需要 admin（可用“admin 会话启动”方式进入）
config status
config show
audit status
audit summary
audit tail 50
audit security 50
audit search COMMAND 50
```

### 9) 危险命令（admin + 二次确认）

> 说明：`mc / redefine / retransform / heapdump / reset / stop` 属于高风险命令。默认需要二次确认：
> 1) 先执行一次命令获取一次性 `token`
> 2) 在 60 秒内追加 `--confirm <token>` 重试同一条命令

```text
# heapdump（示例：导出 live 对象）
heapdump --live --file=/tmp/heapdump.hprof
heapdump --live --file=/tmp/heapdump.hprof --confirm <token>

# reset（清空增强/会话并尽力回滚字节码）
reset
reset --confirm <token>

# stop（停止目标 JVM 内 agent）
stop
stop --confirm <token>

# retransform / redefine / mc（需要准备 class/source 文件，且只允许方法体变更）
retransform *EnhancedTestApplication* --list
retransform *EnhancedTestApplication* --confirm <token>

mc /tmp/YourClass.java -c com.example.YourClass -o /tmp
mc /tmp/YourClass.java -c com.example.YourClass -o /tmp --confirm <token>

redefine com.example.YourClass /tmp/com/example/YourClass.class
redefine com.example.YourClass /tmp/com/example/YourClass.class --confirm <token>
```

## 可选：演示 `auth`（口令认证）

> 默认配置可能关闭口令认证（`security.auth.password.enabled=false`）。如需演示 `auth`，可通过 `JAVA_TOOL_OPTIONS` 临时开启并注入口令（仅 demo 用）。

```bash
docker exec -it java-sleuth-demo bash -lc 'JAVA_TOOL_OPTIONS="-Dsleuth.security.auth.password.enabled=true -Dsleuth.security.auth.admin.password=admin -Dsleuth.security.auth.operator.password=operator -Dsleuth.security.auth.viewer.password=viewer" ./sleuth.sh'
```

在 `sleuth>` 中执行：

```text
auth admin admin
auth operator operator
auth viewer viewer
```

## 清理

```bash
docker rm -f java-sleuth-demo
```
