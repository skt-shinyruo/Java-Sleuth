# Remove HMAC Mode (Loopback-Only) Design

**Date:** 2026-03-05

## Goal

Remove the `security.mode=hmac` feature entirely to avoid multi-process secret propagation pitfalls (Launcher ↔ Agent),
and enforce a simple, explicit security boundary:

- The Java-Sleuth command server binds **only to loopback** (`127.0.0.1` / `localhost` / `::1`).

## Non-Goals

- Supporting remote / non-loopback access to the command port.
- Providing request integrity (signing) over the plaintext TCP connection.
- Implementing a new secret exchange mechanism in the handshake protocol.

## Current State (Problem)

- The system supports `security.mode=off|hmac`.
- The server may auto-generate `security.hmac.secret` on loopback when empty, storing it as a runtime override.
- The Launcher signs commands using its own process-local config; the handshake does not (and should not) transfer secrets.

This makes “server-side auto-generated HMAC secret” architecturally unusable for the Launcher: it is easy to configure a state
where the server starts but the client cannot successfully execute commands.

## Decision

### Security Boundary

- Enforce **loopback-only bind**. Any non-loopback bind must fail-fast at server startup with an actionable error.

### Configuration

- Remove HMAC-related configuration surface:
  - `security.mode`
  - `security.hmac.*`
  - `security.bootstrap.hmac.*`
- If any of these keys appear in a config file or as `-Dsleuth.*` system properties, fail-fast with a clear error.

### Protocol

- Keep the existing text handshake (`HELLO` / `CONFIG` / `UPGRADE BINARY` / `OK`) and binary frames data plane.
- Remove any handshake fields that expose `securityMode` (because it no longer exists).
- If a client sends a `SIG ...` envelope (legacy HMAC wrapper), the server returns an explicit error indicating HMAC has been removed.

### Authorization / Password Auth

- Keep password authentication (`security.auth.password.enabled`) and RBAC (`security.authorization.enabled`) as optional features.
- Since there is no HMAC “session bootstrap” anymore, production templates should use password authentication when enabling RBAC.

## Risks / Mitigations

- **Breaking change:** Existing configs referencing `security.mode` or `security.hmac.*` will fail to load.
  - Mitigation: fail-fast with clear messages and update docs/templates accordingly.
- **Reduced security for remote use:** Without HMAC, remote exposure is unsafe.
  - Mitigation: loopback-only bind is enforced; remote access must be done via SSH port-forwarding, `docker exec`, etc.

## Acceptance Criteria

- Server refuses to start when `server.bind.address` is non-loopback.
- Any usage of `security.mode` / `security.hmac.*` fails fast during config load (file or `-Dsleuth.*`).
- Launcher connects and executes commands without signing.
- `mvn test` passes.

