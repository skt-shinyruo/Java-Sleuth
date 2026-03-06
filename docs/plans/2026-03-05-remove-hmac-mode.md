# Remove HMAC Mode Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove HMAC request signing/verification and enforce loopback-only server bind.

**Architecture:** Treat the command server as a loopback-only local control plane. Remove all HMAC config/schema/model code paths and reject legacy HMAC config keys during config load.

**Tech Stack:** Java, Maven, JUnit4, existing `ProductionConfig`/schema model, text handshake + binary frames.

---

### Task 1: Add RED tests for new boundary + forbidden keys

**Files:**
- Modify: `core/src/test/java/com/javasleuth/core/command/CommandProcessorSecurityBoundaryTest.java`
- Modify: `core/src/test/java/com/javasleuth/foundation/config/ConfigSemanticsTest.java`

**Step 1: Write failing test (non-loopback error message does not mention HMAC)**

- Add a test that starts the server with `server.bind.address=0.0.0.0` and asserts:
  - Startup is blocked
  - Error output does **not** contain `security.mode` or `hmac`

**Step 2: Write failing tests (forbidden config keys)**

- Add tests asserting `new ConfigLoader().load()` throws when the config file contains:
  - `security.mode=hmac`
  - `security.hmac.secret=...`

**Step 3: Run tests to verify RED**

Run: `mvn test -pl core -Dtest=CommandProcessorSecurityBoundaryTest,ConfigSemanticsTest`

Expected: FAIL.

---

### Task 2: Forbid HMAC keys at configuration load time

**Files:**
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/schema/SleuthConfigSchema.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/schema/ConfigSchemaValidator.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/model/SleuthConfigParser.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/config/model/SecurityConfig.java`

**Step 1: Add forbidden keys**

- Add the following to `SleuthConfigSchema.forbiddenKeysInternal()`:
  - `security.mode`
  - `security.hmac.secret`
  - `security.hmac.*` (enumerate concrete keys used by schema)
  - `security.bootstrap.hmac.*`

**Step 2: Remove schema/model code for HMAC**

- Remove `SecurityConfig.Mode` and all HMAC fields/methods.
- Update `SleuthConfigParser.parseSecurity(...)` to stop parsing HMAC-related keys.
- Update schema validator to stop asserting allowed values for removed keys.

**Step 3: Run tests to verify GREEN**

Run: `mvn test -pl core -Dtest=ConfigSemanticsTest`

Expected: PASS.

---

### Task 3: Enforce loopback-only bind in server bootstrapper

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/server/ServerBootstrapper.java`
- Modify: `core/src/test/java/com/javasleuth/core/command/CommandProcessorSecurityBoundaryTest.java`

**Step 1: Remove security.mode/hmac logic**

- Replace the “non-loopback + security.mode=off” conditional with unconditional non-loopback refusal.
- Remove the `ensureHmacSecret(...)` method and related autogen logic.
- Update error message to only mention loopback requirement (no `security.mode` / `hmac`).

**Step 2: Run tests**

Run: `mvn test -pl core -Dtest=CommandProcessorSecurityBoundaryTest`

Expected: PASS.

---

### Task 4: Remove request signing/verification from server request path

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/server/protocol/CommandRequestExecutor.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/server/CommandClientHandler.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java`
- Modify: `core/src/main/java/com/javasleuth/core/agent/runtime/SleuthAgentRuntime.java`
- Delete: `foundation/src/main/java/com/javasleuth/foundation/security/RequestSecurityManager.java`

**Step 1: Remove injection**

- Stop wiring `RequestSecurityManager` into the server runtime and executor chain.

**Step 2: Handle legacy `SIG ...` envelope**

- If a command line starts with `SIG `, reply with a clear error: “HMAC has been removed; send raw command”.

**Step 3: Run core tests**

Run: `mvn test -pl core`

Expected: PASS.

---

### Task 5: Update Launcher to stop generating/passing HMAC config

**Files:**
- Modify: `launcher/src/main/java/com/javasleuth/launcher/attach/AgentArgsBuilder.java`
- Modify: `launcher/src/main/java/com/javasleuth/launcher/client/ProtocolClient.java`
- Modify: `launcher/src/main/java/com/javasleuth/launcher/SleuthLauncher.java`
- Modify: `launcher/src/main/java/com/javasleuth/launcher/cli/LauncherArgs.java`
- Modify: `launcher/src/test/java/com/javasleuth/launcher/cli/LauncherArgsTest.java`

**Step 1: Agent args**

- Stop appending `security.mode` / `security.hmac.*` to agent args.
- Remove deprecated `--insecure` / `--insecure-confirm` flags (breaking change).

**Step 2: Client protocol**

- Remove `RequestSecurityManager.createDefault()` usage.
- Use `CommandSigner.noop()` for all connections.

**Step 3: Run launcher tests**

Run: `mvn test -pl launcher`

Expected: PASS.

---

### Task 6: Update handshake/config output and docs/templates

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/server/protocol/HandshakeNegotiator.java`
- Modify: `launcher/src/main/java/com/javasleuth/launcher/client/HandshakeClient.java` (if needed)
- Modify: `docs/usage/getting-started.md`
- Modify: `docs/usage/troubleshooting.md`
- Modify: `docs/ops/operations-runbook.md`
- Modify: `docs/ops/production-deployment-guide.md`
- Modify: `config-templates/production-sleuth.properties`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/security/CommandSigner.java` (javadoc)

**Step 1: Protocol line cleanup**

- Remove `securityMode=...` from server CONFIG line.

**Step 2: Docs/templates**

- Remove references to `security.mode=hmac` and `security.hmac.secret`.
- Update recommendations: loopback-only bind + (optional) password auth + RBAC.

**Step 3: Full verification**

Run: `mvn test`

Expected: PASS.
