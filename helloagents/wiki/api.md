# API Manual

## Overview
Java-Sleuth 提供基于 TCP 的命令通道，支持 legacy（逐行文本）、framed（文本分帧）、binary（严格二进制帧）三种模式。Launcher 作为客户端与目标 JVM 内的 CommandProcessor 交互。

## Authentication Method
新连接默认创建 viewer 会话（可通过配置关闭匿名会话）。可使用 `auth <user> <password>` 升级角色，AuthorizationManager 依据命令权限与会话角色进行拦截。

---

## API List

### Command Protocol

#### TCP socket://{server.bind.address}:{server.port}
默认：`127.0.0.1:3658`

**Description:** 基于 TCP 的命令交互协议。建议客户端先进行 HELLO/CONFIG 握手以获取服务端实际协议能力与参数。

**Request Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| line | string | Yes | 命令行文本 |

**Handshake（可选，推荐）：**
- Client → Server：`HELLO v=1 protocols=binary,framed,legacy protocol=<preferred>`
- Server → Client：`CONFIG v=1 protocol=<legacy|framed|binary> streaming=<true|false> maxPayload=<int> ...`
- 若选择 binary：Client → Server：`UPGRADE BINARY`，Server → Client：`OK`，随后切换到二进制帧通道

**协议模式：**

1) legacy（逐行文本）
- Request：`<command>\n`
- Response：输出文本行（无显式 END；Launcher 使用“短时间无新数据”的启发式结束）

2) framed（文本分帧）
- Request：
  - `CMD <command>\n`：普通命令
  - `STREAM <command>\n`：流式命令（watch/trace）
- Response：
  - `DATA <len>\n<payload>\n`
  - `ERR <len>\n<payload>\n`
  - `END\n`

3) binary（严格二进制帧）
- Request/Response：`BinaryFrame`（magic/version/type/flags/length + payload bytes）
- 服务端以 `DATA/ERR` 帧输出，最后以 `END` 帧结束

**可选安全增强（security.mode=hmac）：**
- 命令行需封装为 `SIG ts=... nonce=... sig=... cmd=...`
- 服务端验证 HMAC-SHA256 与 nonce 去重（基础防重放）
