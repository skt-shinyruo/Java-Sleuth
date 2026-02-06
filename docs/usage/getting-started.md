# Java-Sleuth 使用指南（快速上手）

> 想查看完整命令与参数说明：请先阅读 `docs/usage/commands.md`（以运行时 `help` 输出为准）。

## 1. 构建

- 需要：JDK 8+（Java 8 需 JDK/`tools.jar`）、Maven 3.6+
- 构建：`mvn clean package`（建议：`mvn verify` 额外做 Java 8 API 兼容性校验）

## 2. 启动 Launcher（交互式 attach）

- Linux/macOS：`./sleuth.sh`
- Windows：`sleuth.bat`
- 或直接运行 fat-jar：`java -jar target/java-sleuth-*-jar-with-dependencies.jar`

## 3. 选择目标 JVM 并开始诊断

- 启动后会列出当前机器上的 Java 进程，按提示选择 PID/序号即可进入 `sleuth>` 交互界面
- 常用命令：`help`、`dashboard`、`thread`、`sc`、`sm`、`watch`、`trace`

## 4. 配置与安全（重要）

- 本地使用推荐保持默认回环绑定（`127.0.0.1`）+ HMAC；不要在未配置密钥的情况下暴露到公网/局域网
- 生产部署、端口、安全与运维：见 `docs/ops/production-deployment-guide.md` 与 `docs/ops/operations-runbook.md`

## 5. Docker 演示环境（纯交互，无需暴露端口）

> 目标：容器启动后先常驻一个 Demo JVM 进程；你再通过 `docker exec -it` 手动进入并运行 `./sleuth.sh`，在 `sleuth>` 中敲命令完成演示。

### 5.1 构建镜像

```bash
docker build -t java-sleuth-demo -f docker/demo/Dockerfile .
```

### 5.2 启动容器（后台运行 Demo JVM）

```bash
docker run -d --name java-sleuth-demo java-sleuth-demo
```

容器默认运行 `com.javasleuth.test.EnhancedTestApplication`（构建阶段从 `examples/` 编译，运行时使用独立 classpath；示例类不在发布 jar/fat-jar 内），会周期性执行业务方法/计算/异常场景，适合演示 `watch/trace/thread` 等命令。

### 5.3 进入容器并启动 Java-Sleuth（交互式）

```bash
docker exec -it java-sleuth-demo ./sleuth.sh
```

进入 `sleuth>` 后，从进程列表里选择 `EnhancedTestApplication` 对应的 PID，然后即可手动演示命令（例如 `dashboard/thread/sc/sm/watch/trace`）。

### 5.4 停止并清理

```bash
docker rm -f java-sleuth-demo
```

## 6. 教学文档（原理与机制）

如果你想进一步理解 Java-Sleuth 的运行机制（为什么 `watch/trace/stack/monitor/tt` 能动态生效），建议阅读教学文档：

- 教学索引：`docs/tutorial/index.md`
- 运行时 Attach 与字节码增强（基础）：`docs/tutorial/attach-and-instrumentation.md`
- 命令触发插桩与回滚（watch/trace/reset/stop）：`docs/tutorial/command-instrumentation-and-rollback.md`
