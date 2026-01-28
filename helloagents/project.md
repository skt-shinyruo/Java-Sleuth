# Project Technical Conventions

---

## Tech Stack
- **Core:** Java 8 / Maven 3
- **Libraries:** ASM 9.x、JLine 3.x、Jackson 2.x
- **Runtime:** Attach API、JMX（可选）

---

## Development Conventions
- **Code Standards:** 维持现有风格与命名习惯，避免引入新的格式化规则
- **Naming Conventions:** Java 类/方法使用驼峰命名；命令名使用小写

---

## Errors and Logging
- **Strategy:** 命令执行失败返回可读错误信息，避免影响目标 JVM 稳定性
- **Logging:** 控制台日志 + 审计日志（可通过配置关闭）

---

## Testing and Process
- **Testing:** JUnit 4 单测为主；脚本覆盖命令回归
- **Commit:** 未定义统一规范，建议保持简洁可追溯

