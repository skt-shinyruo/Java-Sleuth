# Remove Framed Protocol (Binary-Only) Design

**Date:** 2026-02-28

## Goal

Make Java-Sleuth serve **only the official Launcher** by removing the legacy `framed` wire protocol and converging on a single data-plane protocol: **binary frames**.

This reduces protocol surface area, prevents framed/binary behavior drift (e.g. idle-timeout mismatch), and simplifies long-output/streaming behavior.

## Non-Goals

- Supporting third-party clients (custom TCP clients).
- Supporting manual debugging via telnet/nc with `CMD ...` / `STREAM ...`.
- Preserving backward compatibility with older Launcher or existing configs.

## Current State (Problem)

- The server supports two protocols (`framed` and `binary`) with a text handshake and optional upgrade.
- There is duplicated protocol logic in server and client.
- Behavior can drift between protocols. Example: `server.connection.timeout` idle behavior differs between framed and binary.

## Decision

- Keep the **text handshake** as-is (human-readable, versioned: `HELLO v=1` / `CONFIG v=1`).
- Remove **framed data-plane** entirely.
- After handshake, all command requests and replies use **binary frames** (`BinaryFrameCodec`).

## Wire Protocol (Target)

1. Server: welcome line (text)
2. Client -> Server: `HELLO v=1 protocols=binary protocol=binary connId=...`
3. Server -> Client: `CONFIG v=1 protocol=binary ... connId=...`
4. Client -> Server: `UPGRADE BINARY`
5. Server -> Client: `OK`
6. Data-plane: binary frames only

## Configuration Changes

- Remove `protocol.mode` from the config schema/model.
- Enforce config migration by treating `protocol.mode` as a **forbidden key** (fail-fast under default STRICT policy).
- Keep existing:
  - `protocol.streaming.enabled`
  - `protocol.frame.max.payload`
  - `protocol.text.max.line.bytes` (still needed for handshake line limits)

## Server Changes (Core)

- Delete framed handlers/codecs (`FrameCodec`, `Frame`, `FramedReplyChannel`, `FramedClientCommandHandler`).
- Simplify `CommandClientHandler`:
  - only accept handshake
  - require `UPGRADE BINARY`
  - then run `BinaryClientProtocolHandler`
- Simplify `HandshakeNegotiator`:
  - only allow selecting `binary`
  - reject any request for `framed`
- Fix binary idle behavior:
  - `BinaryClientProtocolHandler` catches `SocketTimeoutException` and continues the read loop (do not drop connection on idle).

## Launcher Changes

- Make Launcher always request `binary` and only advertise `protocols=binary`.
- Remove framed send/receive logic from `ProtocolClient` (remove `FrameCodec` path).

## Risks / Mitigations

- **Breaking change:** old configs/clients will fail.
  - Mitigation: fail-fast with clear error message (`protocol.mode` forbidden).
- **Shutdown behavior:** binary read loop must not block forever.
  - Mitigation: keep server-side `SO_TIMEOUT` as a polling timeout and handle `SocketTimeoutException` as normal idle.

## Acceptance Criteria

- `mvn test` passes.
- Launcher interactive/headless mode works end-to-end using binary-only.
- Idle connection does not drop after `server.connection.timeout`.
- `protocol.mode` present in config causes startup to fail with actionable error.
