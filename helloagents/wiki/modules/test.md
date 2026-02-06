# test

## Purpose
示例应用与测试支撑（源码位于 `examples/`，不进入发布 jar/fat-jar）。

## Module Overview
- **Responsibility:** 示例 JVM 进程、测试入口
- **Status:** ✅Stable
- **Last Updated:** 2026-02-06

## Specifications

### Requirement: 示例运行
**Module:** test
提供可附加的 JVM 示例进程。

#### Scenario: 运行测试应用
前置：构建完成  
- 编译 examples：`./scripts/examples/compile-examples.sh`（输出到 `target/examples-classes`）
- 启动 TestApplication：`java -cp target/examples-classes com.javasleuth.test.TestApplication`
- 供 watch/trace 测试

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
N/A

## Change History
- 202601281100_init_kb (planned)
