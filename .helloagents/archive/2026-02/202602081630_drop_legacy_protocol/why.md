# Change Proposal: 移除 legacy 文本协议，统一使用 framed/binary

## Requirement Background
当前工程同时支持 legacy（逐行文本）、framed（文本分帧）、binary（严格二进制帧）三种通道。legacy 逐行文本协议天生缺少显式“响应结束”边界，容易把多行输出误解为多条消息，甚至出现跨命令响应错位。

在明确“没有 legacy 客户端”的前提下，继续保留 legacy 协议只会引入：
- 协议状态机分支与维护成本
- 输出边界/粘包类问题的潜在风险
- 配置项与文档的理解成本

因此，本次收敛协议能力：**移除 legacy 文本协议**，统一使用 framed/binary 两种具备确定边界的协议。

## Change Content
1. **服务端协议收敛：** 不再接受非 `CMD`/`STREAM` 前缀的命令请求；遇到 legacy 请求直接返回协议错误并关闭连接。
2. **握手协商收敛：** HELLO/CONFIG 不再协商/选择 legacy，默认协商到 framed（可选升级 binary）。
3. **客户端（Launcher）收敛：** 不再支持 legacy 读写逻辑，仅走 framed/binary。
4. **配置项与文档同步：** 删除 legacy 相关配置项与说明，避免产生“配置存在但无意义”的误导。

## Impact Scope
- **Modules:** command / launcher / config / docs
- **Behavior:** legacy 客户端将无法连接与执行命令（符合“无 legacy 客户端”前提）
- **Security/Robustness:** framed/binary 具备显式 END 边界，降低输出错位与协议解析歧义风险

## Success Criteria
- 服务端仅接受 framed/binary 请求，legacy 请求被明确拒绝
- Launcher 不再包含 legacy 读写分支
- `protocol.text.end.marker.enabled` 等 legacy 专用配置项与文档被移除
- 单测通过（`mvn test`）

