# compiler

## Purpose
内存 Java 编译支持。

## Module Overview
- **Responsibility:** MemoryJavaCompiler 为热更新提供编译能力
- **Status:** ✅Stable
- **Last Updated:** 2026-01-29

## Specifications

### Requirement: 内存编译
**Module:** compiler
支持 `mc` 命令进行源代码编译。

#### Scenario: 编译源文件
前置：用户提供 Java 源文件  
- 编译为 class 输出
- 供 redefine 使用

#### Notes
- MemoryJavaCompiler 需要确保编译产物能可靠写入 `compiledClasses`（通过在 OutputStream.close 时落盘到内存 map）。
- `mc` 默认会根据 `package` + 主类型名推导 FQCN（若未显式传入 `-c/--class`）。

## API Interfaces
N/A

## Data Models
N/A

## Dependencies
N/A

## Change History
- 202601281100_init_kb (planned)
