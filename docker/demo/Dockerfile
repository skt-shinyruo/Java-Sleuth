# syntax=docker/dockerfile:1

FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY foundation/pom.xml ./foundation/pom.xml
COPY agent/pom.xml ./agent/pom.xml
COPY core/pom.xml ./core/pom.xml
COPY launcher/pom.xml ./launcher/pom.xml
COPY examples/pom.xml ./examples/pom.xml
RUN mvn -q -DskipTests -pl foundation,agent,core,launcher,examples dependency:go-offline

COPY foundation/src ./foundation/src
COPY agent/src ./agent/src
COPY core/src ./core/src
COPY launcher/src ./launcher/src
COPY examples/src ./examples/src
COPY scripts/examples ./scripts/examples
RUN mvn -q -DskipTests package
RUN bash ./scripts/examples/compile-examples.sh > /dev/null

FROM eclipse-temurin:8-jdk
WORKDIR /opt/java-sleuth

COPY sleuth.sh ./sleuth.sh
RUN chmod +x ./sleuth.sh

COPY --from=build /workspace/launcher/target/java-sleuth-launcher*-jar-with-dependencies.jar ./target/
COPY --from=build /workspace/agent/target/java-sleuth-agent*-jar-with-dependencies.jar ./target/
COPY --from=build /workspace/core/target/java-sleuth-agent-core*-jar-with-dependencies.jar ./target/
COPY --from=build /workspace/target/examples-classes ./examples-classes

# 默认启动 Demo JVM 进程（用于 docker exec 后手动 attach 演示）
CMD ["bash", "-lc", "java -cp /opt/java-sleuth/examples-classes com.javasleuth.test.EnhancedTestApplication"]
