# Remove Framed Protocol (Binary-Only) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove the `framed` protocol end-to-end and serve only the official Launcher over a binary-frame data plane.

**Architecture:** Keep the existing text handshake (`HELLO`/`CONFIG`/`UPGRADE BINARY`/`OK`) but require `protocol=binary` and always upgrade to binary frames for all command request/response traffic.

**Tech Stack:** Java, Maven, TCP sockets, `BufferedInputStream`/`BufferedOutputStream`, `DataInputStream`/`DataOutputStream` binary framing.

---

### Task 1: Make Launcher Handshake Binary-Only

**Files:**
- Modify: `launcher/src/main/java/com/javasleuth/launcher/client/HandshakeClient.java`
- Test: `launcher/src/test/java/com/javasleuth/launcher/client/HandshakeClientTest.java`

**Step 1: Update the hello-line builder to default to binary**

Change `HandshakeClient.buildHelloLine(...)` so that:
- The requested protocol defaults to `binary`.
- The advertised protocols list is only `binary`.

Target output example:
- `HELLO v=1 protocols=binary protocol=binary connId=<id>`

**Step 2: Update HandshakeClientTest**

Update `testBuildHelloLineDefaultsToFramed()` to assert `protocol=binary` instead of `protocol=framed`.

**Step 3: Run launcher unit tests**

Run: `mvn test -q -Dtest=com.javasleuth.launcher.client.HandshakeClientTest`

Expected: PASS

**Step 4: Commit**

Run:
```bash
git add launcher/src/main/java/com/javasleuth/launcher/client/HandshakeClient.java \
        launcher/src/test/java/com/javasleuth/launcher/client/HandshakeClientTest.java

git commit -m "launcher: default handshake to binary"
```

---

### Task 2: Make Server Handshake Negotiation Binary-Only

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/server/protocol/HandshakeNegotiator.java`

**Step 1: Reject framed during handshake**

In `HandshakeNegotiator.handleHello(...)`, change validation so that:
- Only `protocol=binary` is accepted.
- Any request for `framed` fails fast with a clear `IOException`.

Keep writing `CONFIG v=1 protocol=binary ...`.

**Step 2: Run core tests that cover handshake/config parsing indirectly**

Run: `mvn test -q -Dtest=com.javasleuth.launcher.client.ProtocolClientIntegrationTest`

Expected: likely FAIL at this stage (because Launcher/ProtocolClient still has framed paths). That is OK; proceed to Task 4 before re-running.

**Step 3: Commit**

Run:
```bash
git add core/src/main/java/com/javasleuth/core/command/server/protocol/HandshakeNegotiator.java

git commit -m "server: require binary protocol in handshake"
```

---

### Task 3: Remove protocol.mode From Schema/Model and Enforce Migration

**Files:**
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/schema/SleuthConfigSchema.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigSchemaValidator.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/model/ProtocolConfig.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfigParser.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/ConfigLoader.java` (if needed for error message only)
- Modify: `launcher/src/main/java/com/javasleuth/launcher/attach/AgentArgsBuilder.java`
- Modify: `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/ConfigCommand.java`
- Modify: `foundation/src/main/resources/sleuth-default.properties`
- Modify: `config-templates/production-sleuth.properties`
- Modify: `docs/ops/production-deployment-guide.md`
- Modify: `README.md`
- Test: `core/src/test/java/com/javasleuth/config/DefaultConfigConsistencyTest.java`
- Test: `core/src/test/java/com/javasleuth/config/SleuthConfigParserTest.java`

**Step 1: Remove PROTOCOL_MODE from SleuthConfigSchema**

Delete the `PROTOCOL_MODE` key definition and registration.

**Step 2: Mark protocol.mode as forbidden**

Add `protocol.mode` into `SleuthConfigSchema` forbidden key set so that configs containing `protocol.mode` fail fast (default forbidden-key policy is STRICT).

Expected runtime error (example):
- `Unsupported config key: protocol.mode (legacy protocol removed)`

**Step 3: Update ConfigSchemaValidator**

Remove `PROTOCOL_MODE` checks:
- remove `assertFailFast(..., SleuthConfigSchema.PROTOCOL_MODE)`
- remove `assertAllowedContainsDefault(..., SleuthConfigSchema.PROTOCOL_MODE)`
- remove the hardcoded default whitelist check for `protocol.mode`.

**Step 4: Remove mode from ProtocolConfig**

In `ProtocolConfig`:
- Remove `Mode` enum and `mode` field.
- Remove `getMode()` and `getModeWireName()`.
- Keep `streamingEnabled`, `frameMaxPayloadBytes`, `textMaxLineBytes`.

Update constructor accordingly.

**Step 5: Update SleuthConfigParser**

In `parseProtocol(...)`:
- Stop reading `protocol.mode`.
- Construct the new `ProtocolConfig` without a mode.
- Delete `parseProtocolMode(...)` helper.

**Step 6: Update callers**

Update:
- `HandshakeNegotiator` to no longer rely on `config.protocol().getModeWireName()`.
- `SleuthLauncher` to no longer pass `typed.protocol().getModeWireName()`.
- `AgentArgsBuilder` to no longer append `protocol.mode=...`.
- `StatusCommand` to remove or hardcode the protocol line (e.g. `Protocol: binary`).
- `ConfigCommand` output to remove `protocol.mode` line.

**Step 7: Update default config + templates + docs**

Remove `protocol.mode=framed` from:
- `foundation/src/main/resources/sleuth-default.properties`
- `config-templates/production-sleuth.properties`
- docs references and README references.

**Step 8: Update config tests**

In `core/src/test/java/com/javasleuth/config/SleuthConfigParserTest.java`:
- Remove `defaults.setProperty("protocol.mode", "framed")` from all test setups.
- Remove or rewrite `shouldFailFastOnInvalidProtocolModeWhenExplicitlyConfigured()` (since protocol.mode is removed).

In `core/src/test/java/com/javasleuth/config/DefaultConfigConsistencyTest.java`:
- Ensure `sleuth-default.properties` contains no unknown keys and matches schema.

**Step 9: Run config tests**

Run: `mvn test -q -Dtest=com.javasleuth.foundation.config.DefaultConfigConsistencyTest,com.javasleuth.foundation.config.SleuthConfigParserTest`

Expected: PASS

**Step 10: Commit**

Run:
```bash
git add foundation/src/main/java/com/javasleuth/foundation/config/schema/SleuthConfigSchema.java \
        foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigSchemaValidator.java \
        foundation/src/main/java/com/javasleuth/foundation/config/model/ProtocolConfig.java \
        foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfigParser.java \
        foundation/src/main/resources/sleuth-default.properties \
        config-templates/production-sleuth.properties \
        core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java \
        core/src/main/java/com/javasleuth/core/command/impl/ConfigCommand.java \
        launcher/src/main/java/com/javasleuth/launcher/attach/AgentArgsBuilder.java \
        launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java \
        core/src/test/java/com/javasleuth/config/SleuthConfigParserTest.java \
        core/src/test/java/com/javasleuth/config/DefaultConfigConsistencyTest.java \
        docs/ops/production-deployment-guide.md \
        README.md

git commit -m "config: remove protocol.mode and enforce binary-only"
```

---

### Task 4: Make Launcher ProtocolClient Binary-Only

**Files:**
- Modify: `launcher/src/main/java/com/javasleuth/launcher/client/ProtocolClient.java`
- Test: `launcher/src/test/java/com/javasleuth/launcher/client/ProtocolClientIntegrationTest.java`

**Step 1: Remove framed fields and framed read loop**

In `ProtocolClient`:
- Remove `framed` boolean field and `isFramed()`.
- Remove `Frame` / `FrameCodec` imports and framed read loop.
- In handshake, require negotiated protocol to be `binary` only.
- Always perform `UPGRADE BINARY` and use `BinaryFrameCodec` for request/response.

**Step 2: Update ProtocolClientIntegrationTest to use binary**

In `ProtocolClientIntegrationTest`:
- Remove runtime override `protocol.mode=framed`.
- Pass `binary` as preferred protocol if signature still requires it, or update call signature if removed.
- Rename test(s) from `...OverFramed` to `...OverBinary`.

**Step 3: Run launcher integration test**

Run: `mvn test -q -Dtest=com.javasleuth.launcher.client.ProtocolClientIntegrationTest`

Expected: PASS

**Step 4: Commit**

Run:
```bash
git add launcher/src/main/java/com/javasleuth/launcher/client/ProtocolClient.java \
        launcher/src/test/java/com/javasleuth/launcher/client/ProtocolClientIntegrationTest.java

git commit -m "launcher: remove framed protocol support"
```

---

### Task 5: Remove Framed Data-Plane From Server (Binary-Only) + Fix Idle Timeout

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/server/CommandClientHandler.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/server/protocol/BinaryClientProtocolHandler.java`
- Delete: `core/src/main/java/com/javasleuth/core/command/server/protocol/FramedClientCommandHandler.java`
- Delete: `core/src/main/java/com/javasleuth/core/command/server/protocol/FramedReplyChannel.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/server/protocol/CommandRequestExecutor.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandContext.java`
- Update tests that construct `CommandContext` directly

**Step 1: Make CommandClientHandler binary-only**

In `CommandClientHandler`:
- Remove `FramedClientCommandHandler` creation.
- After successful `HELLO`, require `UPGRADE BINARY`.
- Remove `CMD ...` / `STREAM ...` text request parsing and the framed handler execution branch.
- For any non-handshake line before upgrade, reply with a clear error like `Send: UPGRADE BINARY`.

**Step 2: Fix binary idle timeout behavior**

In `BinaryClientProtocolHandler.handle(...)`:
- Wrap `BinaryFrameCodec.readFrame(...)` in a `try/catch`.
- `catch (java.net.SocketTimeoutException)` and `continue` the loop.

This makes `server.connection.timeout` behave like a polling read timeout (no idle disconnect).

**Step 3: Remove framed flag from CommandContext**

In `CommandContext`:
- Remove `framed` field and `isFramed()`.
- Update constructors accordingly.

In `CommandRequestExecutor.execute(...)`:
- Remove the `framedRequested` parameter.
- When building `CommandContext`, stop passing framedRequested.

Update all call sites and tests that construct `CommandContext`.

**Step 4: Add a focused unit test for SocketTimeoutException in binary handler**

Create: `core/src/test/java/com/javasleuth/command/BinaryClientProtocolHandlerTimeoutTest.java`

Test idea:
- Build a `DataInputStream` backed by an `InputStream` that throws `SocketTimeoutException` on first read, then returns EOF.
- Call `BinaryClientProtocolHandler.handle(...)`.
- Assert: method returns without throwing.

**Step 5: Run core tests**

Run: `mvn test -q -Dtest=com.javasleuth.command.BinaryClientProtocolHandlerTimeoutTest`

Expected: PASS

**Step 6: Commit**

Run:
```bash
git add core/src/main/java/com/javasleuth/core/command/server/CommandClientHandler.java \
        core/src/main/java/com/javasleuth/core/command/server/protocol/BinaryClientProtocolHandler.java \
        core/src/main/java/com/javasleuth/core/command/server/protocol/CommandRequestExecutor.java \
        core/src/main/java/com/javasleuth/core/command/CommandContext.java \
        core/src/test/java/com/javasleuth/command/BinaryClientProtocolHandlerTimeoutTest.java

git rm core/src/main/java/com/javasleuth/core/command/server/protocol/FramedClientCommandHandler.java \
       core/src/main/java/com/javasleuth/core/command/server/protocol/FramedReplyChannel.java

git commit -m "server: remove framed protocol and keep binary connections idle-safe"
```

---

### Task 6: Remove Frame/FrameCodec From Foundation + Update Tests

**Files:**
- Delete: `foundation/src/main/java/com/javasleuth/foundation/command/protocol/Frame.java`
- Delete: `foundation/src/main/java/com/javasleuth/foundation/command/protocol/FrameCodec.java`
- Modify: `core/src/test/java/com/javasleuth/command/CommandProcessorTest.java`
- Modify: `launcher/src/main/java/com/javasleuth/launcher/client/ProtocolClient.java` (remove imports if still present)

**Step 1: Delete Frame and FrameCodec**

Remove both files from the foundation module.

**Step 2: Update CommandProcessorTest**

In `CommandProcessorTest`:
- Remove `testFrameCodecRoundTrip()`.
- Remove `testFrameCodecRoundTripStream()`.
- Remove `Frame` / `FrameCodec` imports.

Keep the `BinaryFrameCodec` tests.

**Step 3: Run affected tests**

Run: `mvn test -q -Dtest=com.javasleuth.core.command.CommandProcessorTest`

Expected: PASS

**Step 4: Commit**

Run:
```bash
git rm foundation/src/main/java/com/javasleuth/foundation/command/protocol/Frame.java \
       foundation/src/main/java/com/javasleuth/foundation/command/protocol/FrameCodec.java

git add core/src/test/java/com/javasleuth/command/CommandProcessorTest.java

git commit -m "foundation: remove framed frame codec"
```

---

### Task 7: Repo-Wide Cleanup + Full Verification

**Files:**
- Modify: any remaining references found by ripgrep

**Step 1: Ensure no framed references remain**

Run:
- `rg -n "\\bframed\\b" -S .`
- `rg -n "FrameCodec" -S .`

Expected:
- No runtime protocol references to framed.
- Only historical/design docs may mention framed (acceptable), but no code should.

**Step 2: Run full test suite**

Run: `mvn test`

Expected: PASS

**Step 3: Commit final cleanup**

Run:
```bash
git add -A

git commit -m "chore: cleanup after removing framed protocol"
```
