# syntax=docker/dockerfile:1

FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
COPY examples ./examples
COPY scripts/examples ./scripts/examples
RUN mvn -q -DskipTests package
RUN bash ./scripts/examples/compile-examples.sh > /dev/null

FROM eclipse-temurin:8-jdk
WORKDIR /opt/java-sleuth

COPY sleuth.sh ./sleuth.sh
RUN chmod +x ./sleuth.sh

COPY --from=build /workspace/target/*-jar-with-dependencies.jar ./target/
COPY --from=build /workspace/target/examples-classes ./examples-classes

# 默认启动 Demo JVM 进程（用于 docker exec 后手动 attach 演示）
CMD ["bash", "-lc", "java -cp /opt/java-sleuth/examples-classes com.javasleuth.test.EnhancedTestApplication"]
