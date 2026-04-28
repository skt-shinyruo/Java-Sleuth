# Command Capability Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move command bootstrap bridge requirements and command capability traits into `CommandMeta`, then make `PrecheckPipeline` read those capabilities instead of hard-coding command names.

**Architecture:** `foundation` owns the dependency-free metadata API: `CommandCapability` plus new immutable fields on `CommandMeta`. `core` declares built-in command capabilities at registration time and enforces bootstrap requirements in `PrecheckPipeline` by reading `CommandMeta`. Transformer-level bootstrap checks remain unchanged as a runtime safety net.

**Tech Stack:** Java 8, Maven multi-module build, JUnit 4, existing `CommandMeta` fluent-copy style, existing `CommandPipeline`/`PrecheckPipeline` test patterns.

---

## File Structure

- Create `foundation/src/main/java/com/javasleuth/foundation/security/CommandCapability.java`
  - Dependency-free enum for command traits.
- Modify `foundation/src/main/java/com/javasleuth/foundation/security/CommandMeta.java`
  - Add immutable capability and bootstrap requirement fields.
  - Preserve existing constructors and fluent methods.
- Create `core/src/test/java/com/javasleuth/security/CommandMetaCapabilityTest.java`
  - Tests the foundation metadata API from the core test module, where JUnit already exists.
- Modify `core/src/main/java/com/javasleuth/core/command/pipeline/PrecheckPipeline.java`
  - Replace `requiredBootstrapClassForCommand` switch with metadata lookup.
- Create `core/src/test/java/com/javasleuth/command/pipeline/PrecheckPipelineBootstrapCapabilityTest.java`
  - Tests bridge precheck behavior without depending on built-in command names.
- Modify `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
  - Declare `BootstrapBridge.SPY_API` and capabilities on built-in instrumentation commands.
- Create `core/src/test/java/com/javasleuth/command/BuiltinCommandCapabilityTest.java`
  - Verifies built-in metadata for instrumentation and non-instrumentation commands.
- Create `docs/dev/command-provider-metadata.md`
  - Short plugin/provider author documentation.
- Modify `docs/index.md`
  - Link the new developer doc.

---

### Task 1: Add Capability Metadata API

**Files:**
- Create: `foundation/src/main/java/com/javasleuth/foundation/security/CommandCapability.java`
- Modify: `foundation/src/main/java/com/javasleuth/foundation/security/CommandMeta.java`
- Test: `core/src/test/java/com/javasleuth/security/CommandMetaCapabilityTest.java`

- [ ] **Step 1: Write the failing metadata tests**

Create `core/src/test/java/com/javasleuth/security/CommandMetaCapabilityTest.java`:

```java
package com.javasleuth.security;

import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class CommandMetaCapabilityTest {
    @Test
    public void defaultMetaHasNoCapabilitiesOrBootstrapRequirements() {
        CommandMeta meta = CommandMeta.viewer(true, false);

        Assert.assertFalse(meta.hasCapability(CommandCapability.USES_INSTRUMENTATION));
        Assert.assertFalse(meta.requiresBootstrap());
        Assert.assertTrue(meta.getCapabilities().isEmpty());
        Assert.assertTrue(meta.getRequiredBootstrapClasses().isEmpty());
    }

    @Test
    public void requiresBootstrapRecordsClassAndImpliesInstrumentationCapability() {
        CommandMeta meta = CommandMeta.operator(false, true)
            .requiresBootstrap("com.example.Bridge");

        Assert.assertTrue(meta.requiresBootstrap());
        Assert.assertTrue(meta.hasCapability(CommandCapability.USES_INSTRUMENTATION));
        Assert.assertTrue(meta.getRequiredBootstrapClasses().contains("com.example.Bridge"));
    }

    @Test
    public void existingCopyMethodsPreserveCapabilitiesAndBootstrapRequirements() {
        CommandMeta meta = CommandMeta.operator(false, true)
            .requiresBootstrap("com.example.Bridge")
            .withCapability(CommandCapability.LONG_RUNNING)
            .withRateLimit(3)
            .withImpact(CommandMeta.ImpactLevel.MEDIUM)
            .withAudit(false)
            .withSubcommandRole("set", com.javasleuth.foundation.security.AuthenticationManager.UserRole.ADMIN);

        Assert.assertTrue(meta.hasCapability(CommandCapability.USES_INSTRUMENTATION));
        Assert.assertTrue(meta.hasCapability(CommandCapability.LONG_RUNNING));
        Assert.assertTrue(meta.getRequiredBootstrapClasses().contains("com.example.Bridge"));
        Assert.assertEquals(3, meta.getMaxExecutionsPerMinute());
        Assert.assertEquals(CommandMeta.ImpactLevel.MEDIUM, meta.getImpactLevel());
        Assert.assertFalse(meta.isRequiresAudit());
    }

    @Test
    public void blankBootstrapClassesAreIgnored() {
        CommandMeta meta = CommandMeta.viewer(false, false)
            .requiresBootstrap(null)
            .requiresBootstrap("")
            .requiresBootstrap("   ");

        Assert.assertFalse(meta.requiresBootstrap());
        Assert.assertTrue(meta.getRequiredBootstrapClasses().isEmpty());
    }

    @Test
    public void collectionsAreImmutableAndBulkApisDeduplicateValues() {
        CommandMeta meta = CommandMeta.operator(false, false)
            .withCapabilities(Arrays.asList(
                CommandCapability.LONG_RUNNING,
                CommandCapability.LONG_RUNNING,
                null
            ))
            .requiresBootstrap(Arrays.asList(
                "com.example.Bridge",
                "com.example.Bridge",
                "  ",
                null
            ));

        Assert.assertEquals(2, meta.getCapabilities().size());
        Assert.assertEquals(1, meta.getRequiredBootstrapClasses().size());

        try {
            meta.getCapabilities().add(CommandCapability.WRITES_DISK);
            Assert.fail("Expected immutable capabilities");
        } catch (UnsupportedOperationException expected) {
            // ok
        }

        try {
            meta.getRequiredBootstrapClasses().add("x.Y");
            Assert.fail("Expected immutable bootstrap requirements");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
```

- [ ] **Step 2: Run the failing metadata tests**

Run:

```bash
mvn -pl core -Dtest=CommandMetaCapabilityTest test
```

Expected: compile failure because `CommandCapability` and the new `CommandMeta` methods do not exist.

- [ ] **Step 3: Add `CommandCapability`**

Create `foundation/src/main/java/com/javasleuth/foundation/security/CommandCapability.java`:

```java
package com.javasleuth.foundation.security;

public enum CommandCapability {
    USES_INSTRUMENTATION,
    LONG_RUNNING,
    WRITES_DISK
}
```

- [ ] **Step 4: Extend `CommandMeta` fields and constructor plumbing**

Modify `foundation/src/main/java/com/javasleuth/foundation/security/CommandMeta.java`.

Add imports:

```java
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
```

Add fields after `subcommandRoles`:

```java
    private final Set<CommandCapability> capabilities;
    private final Set<String> requiredBootstrapClasses;
```

Update the two public constructors so they pass empty sets:

```java
    public CommandMeta(UserRole requiredRole, boolean cacheable, boolean streamable) {
        this(requiredRole, cacheable, streamable,
            defaultRequiresAudit(requiredRole),
            defaultRateLimit(requiredRole),
            false,
            defaultImpact(false),
            Collections.emptyMap(),
            Collections.<CommandCapability>emptySet(),
            Collections.<String>emptySet());
    }

    public CommandMeta(UserRole requiredRole,
                       boolean cacheable,
                       boolean streamable,
                       boolean requiresAudit,
                       int maxExecutionsPerMinute,
                       boolean dangerous) {
        this(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            defaultImpact(dangerous),
            Collections.emptyMap(),
            Collections.<CommandCapability>emptySet(),
            Collections.<String>emptySet());
    }
```

Replace the private constructor signature and tail assignments with:

```java
    private CommandMeta(UserRole requiredRole,
                        boolean cacheable,
                        boolean streamable,
                        boolean requiresAudit,
                        int maxExecutionsPerMinute,
                        boolean dangerous,
                        ImpactLevel impact,
                        Map<String, UserRole> subcommandRoles,
                        Set<CommandCapability> capabilities,
                        Set<String> requiredBootstrapClasses) {
        this.requiredRole = requiredRole;
        this.cacheable = cacheable;
        this.streamable = streamable;
        this.requiresAudit = requiresAudit;
        this.maxExecutionsPerMinute = maxExecutionsPerMinute;
        this.dangerous = dangerous;
        this.impact = impact != null ? impact : ImpactLevel.LOW;
        this.subcommandRoles = subcommandRoles != null ? subcommandRoles : Collections.emptyMap();
        this.capabilities = immutableCapabilities(capabilities);
        this.requiredBootstrapClasses = immutableBootstrapClasses(requiredBootstrapClasses);
    }
```

- [ ] **Step 5: Add `CommandMeta` getters and fluent APIs**

In `CommandMeta`, add these methods after `getImpactLevel()`:

```java
    public boolean hasCapability(CommandCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    public Set<CommandCapability> getCapabilities() {
        return capabilities;
    }

    public boolean requiresBootstrap() {
        return !requiredBootstrapClasses.isEmpty();
    }

    public Set<String> getRequiredBootstrapClasses() {
        return requiredBootstrapClasses;
    }
```

Add these fluent APIs after `withRateLimit(...)`:

```java
    public CommandMeta withCapability(CommandCapability capability) {
        if (capability == null) {
            return this;
        }
        Set<CommandCapability> next = new LinkedHashSet<>(capabilities);
        next.add(capability);
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, next, requiredBootstrapClasses);
    }

    public CommandMeta withCapabilities(Collection<CommandCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return this;
        }
        Set<CommandCapability> next = new LinkedHashSet<>(this.capabilities);
        for (CommandCapability c : capabilities) {
            if (c != null) {
                next.add(c);
            }
        }
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, next, requiredBootstrapClasses);
    }

    public CommandMeta requiresBootstrap(String binaryClassName) {
        if (binaryClassName == null || binaryClassName.trim().isEmpty()) {
            return this;
        }
        Set<String> nextBootstrap = new LinkedHashSet<>(requiredBootstrapClasses);
        nextBootstrap.add(binaryClassName.trim());
        Set<CommandCapability> nextCapabilities = new LinkedHashSet<>(capabilities);
        nextCapabilities.add(CommandCapability.USES_INSTRUMENTATION);
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, nextCapabilities, nextBootstrap);
    }

    public CommandMeta requiresBootstrap(Collection<String> binaryClassNames) {
        if (binaryClassNames == null || binaryClassNames.isEmpty()) {
            return this;
        }
        CommandMeta next = this;
        for (String name : binaryClassNames) {
            next = next.requiresBootstrap(name);
        }
        return next;
    }
```

- [ ] **Step 6: Preserve new fields in existing copy methods**

Update existing fluent methods in `CommandMeta` so each constructor call includes `capabilities` and `requiredBootstrapClasses`.

Use this exact pattern:

```java
    public CommandMeta withAudit(boolean requiresAudit) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, capabilities, requiredBootstrapClasses);
    }

    public CommandMeta withRateLimit(int maxExecutionsPerMinute) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact, subcommandRoles, capabilities, requiredBootstrapClasses);
    }

    public CommandMeta withDangerous(boolean dangerous) {
        ImpactLevel nextImpact = impact;
        if (dangerous && (nextImpact == null || nextImpact == ImpactLevel.LOW)) {
            nextImpact = ImpactLevel.HIGH;
        }
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            nextImpact, subcommandRoles, capabilities, requiredBootstrapClasses);
    }

    public CommandMeta withImpact(ImpactLevel impact) {
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous,
            impact != null ? impact : ImpactLevel.LOW,
            subcommandRoles, capabilities, requiredBootstrapClasses);
    }
```

Update `withSubcommandRole(...)` return:

```java
        return new CommandMeta(requiredRole, cacheable, streamable, requiresAudit, maxExecutionsPerMinute, dangerous, impact,
            Collections.unmodifiableMap(next), capabilities, requiredBootstrapClasses);
```

- [ ] **Step 7: Add immutable helper methods**

Add these private helpers near the bottom of `CommandMeta`, before `defaultRequiresAudit(...)`:

```java
    private static Set<CommandCapability> immutableCapabilities(Set<CommandCapability> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptySet();
        }
        Set<CommandCapability> out = new LinkedHashSet<>();
        for (CommandCapability c : input) {
            if (c != null) {
                out.add(c);
            }
        }
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
    }

    private static Set<String> immutableBootstrapClasses(Set<String> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : input) {
            if (s == null) {
                continue;
            }
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
    }
```

- [ ] **Step 8: Run the metadata tests**

Run:

```bash
mvn -pl core -Dtest=CommandMetaCapabilityTest test
```

Expected: PASS.

- [ ] **Step 9: Commit metadata API**

```bash
git add foundation/src/main/java/com/javasleuth/foundation/security/CommandCapability.java \
  foundation/src/main/java/com/javasleuth/foundation/security/CommandMeta.java \
  core/src/test/java/com/javasleuth/security/CommandMetaCapabilityTest.java
git commit -m "feat: add command capability metadata"
```

---

### Task 2: Make Bootstrap Precheck Metadata-Driven

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/pipeline/PrecheckPipeline.java`
- Test: `core/src/test/java/com/javasleuth/command/pipeline/PrecheckPipelineBootstrapCapabilityTest.java`

- [ ] **Step 1: Write the failing precheck tests**

Create `core/src/test/java/com/javasleuth/command/pipeline/PrecheckPipelineBootstrapCapabilityTest.java`:

```java
package com.javasleuth.command.pipeline;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandPipeline;
import com.javasleuth.core.command.CommandRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import org.junit.Assert;
import org.junit.Test;

public class PrecheckPipelineBootstrapCapabilityTest {
    @Test
    public void bootstrapRequirementFromMetaDeniesNonHelpInvocation() throws Exception {
        withPipeline((pipeline) -> {
            CommandRegistry.Entry entry = new CommandRegistry.Entry(
                fixedCommand(),
                CommandMeta.operator(false, false).requiresBootstrap("missing.TestBridge"),
                "test"
            );

            CommandPipeline.PrecheckResult result = pipeline.precheck(
                entry,
                "custom",
                new String[]{"custom", "run"},
                new CommandContext("client", "test", null, false)
            );

            Assert.assertFalse(result.isOk());
            Assert.assertTrue(result.getError().contains("missing.TestBridge"));
            Assert.assertTrue(result.getError().contains("custom"));
        });
    }

    @Test
    public void bootstrapRequirementFromMetaAllowsHelpInvocation() throws Exception {
        withPipeline((pipeline) -> {
            CommandRegistry.Entry entry = new CommandRegistry.Entry(
                fixedCommand(),
                CommandMeta.operator(false, false).requiresBootstrap("missing.TestBridge"),
                "test"
            );

            CommandPipeline.PrecheckResult result = pipeline.precheck(
                entry,
                "custom",
                new String[]{"custom", "--help"},
                new CommandContext("client", "test", null, false)
            );

            Assert.assertTrue(result.isOk());
        });
    }

    @Test
    public void commandNameAloneNoLongerTriggersBootstrapCheck() throws Exception {
        withPipeline((pipeline) -> {
            CommandRegistry.Entry entry = new CommandRegistry.Entry(
                fixedCommand(),
                CommandMeta.operator(false, false),
                "test"
            );

            CommandPipeline.PrecheckResult result = pipeline.precheck(
                entry,
                "watch",
                new String[]{"watch", "run"},
                new CommandContext("client", "test", null, false)
            );

            Assert.assertTrue(result.isOk());
        });
    }

    private static Command fixedCommand() {
        return new Command() {
            @Override
            public String execute(String[] args) {
                return "ok";
            }

            @Override
            public String getDescription() {
                return "fixed";
            }
        };
    }

    private static void withPipeline(PipelineConsumer consumer) throws Exception {
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        try {
            System.setProperty("sleuth.security.authorization.enabled", "false");
            System.setProperty("sleuth.security.input.validation", "false");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger);
                PerformanceOptimizer optimizer = new PerformanceOptimizer(config)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                InputValidator validator = new InputValidator(config, auditLogger);
                CommandPipeline pipeline = new CommandPipeline(validator, authz, dangerousConfirm, config, optimizer);
                try {
                    consumer.accept(pipeline);
                } finally {
                    pipeline.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.authorization.enabled", oldAuthz);
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private interface PipelineConsumer {
        void accept(CommandPipeline pipeline) throws Exception;
    }
}
```

- [ ] **Step 2: Run the failing precheck tests**

Run:

```bash
mvn -pl core -Dtest=PrecheckPipelineBootstrapCapabilityTest test
```

Expected: the first test fails because the precheck still ignores metadata; the third test fails because the command name `watch` still triggers the hard-coded switch.

- [ ] **Step 3: Replace the hard-coded bridge lookup**

Modify `core/src/main/java/com/javasleuth/core/command/pipeline/PrecheckPipeline.java`.

Add imports:

```java
import java.util.Collections;
import java.util.Set;
```

In `BootstrapBridgeStep.apply(...)`, replace the `requiredBootstrapClassForCommand(cmd)` block with:

```java
            Set<String> required = state.meta != null
                ? state.meta.getRequiredBootstrapClasses()
                : Collections.<String>emptySet();
            if (required.isEmpty()) {
                return null;
            }
```

Keep the existing help-like bypass after that block.

Replace the single class check:

```java
            if (BootstrapBridge.canEnableEnhancement(required, null)) {
                return null;
            }

            return PrecheckDecision.denied(BootstrapBridge.formatDisabledMessage(cmd, required), state.argsForChecks);
```

with:

```java
            for (String requiredClass : required) {
                if (BootstrapBridge.canEnableEnhancement(requiredClass, null)) {
                    continue;
                }
                return PrecheckDecision.denied(
                    BootstrapBridge.formatDisabledMessage(cmd, requiredClass),
                    state.argsForChecks
                );
            }
            return null;
```

Delete the entire `requiredBootstrapClassForCommand(String cmd)` method.

- [ ] **Step 4: Run the precheck tests**

Run:

```bash
mvn -pl core -Dtest=PrecheckPipelineBootstrapCapabilityTest test
```

Expected: PASS.

- [ ] **Step 5: Commit metadata-driven precheck**

```bash
git add core/src/main/java/com/javasleuth/core/command/pipeline/PrecheckPipeline.java \
  core/src/test/java/com/javasleuth/command/pipeline/PrecheckPipelineBootstrapCapabilityTest.java
git commit -m "refactor: drive bootstrap precheck from command metadata"
```

---

### Task 3: Annotate Built-In Command Capabilities

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- Test: `core/src/test/java/com/javasleuth/command/BuiltinCommandCapabilityTest.java`

- [ ] **Step 1: Write the failing built-in metadata test**

Create `core/src/test/java/com/javasleuth/command/BuiltinCommandCapabilityTest.java`:

```java
package com.javasleuth.command;

import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.core.command.BuiltinCommandProvider;
import com.javasleuth.core.command.CommandDescriptor;
import com.javasleuth.core.command.CommandProviderContext;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class BuiltinCommandCapabilityTest {
    @Test
    public void instrumentationCommandsDeclareSpyApiBootstrapRequirement() throws Exception {
        withDescriptors((descriptors) -> {
            Map<String, CommandMeta> metaByName = byName(descriptors);
            for (String name : new String[]{"watch", "trace", "monitor", "tt", "stack", "vmtool"}) {
                CommandMeta meta = metaByName.get(name);
                Assert.assertNotNull("missing command: " + name, meta);
                Assert.assertTrue(name + " should require bootstrap", meta.requiresBootstrap());
                Assert.assertTrue(name + " should require SpyAPI",
                    meta.getRequiredBootstrapClasses().contains(BootstrapBridge.SPY_API));
                Assert.assertTrue(name + " should use instrumentation",
                    meta.hasCapability(CommandCapability.USES_INSTRUMENTATION));
            }
        });
    }

    @Test
    public void selectedNonInstrumentationCommandsDoNotRequireBootstrap() throws Exception {
        withDescriptors((descriptors) -> {
            Map<String, CommandMeta> metaByName = byName(descriptors);
            for (String name : new String[]{"thread", "jvm", "jobs", "status"}) {
                CommandMeta meta = metaByName.get(name);
                Assert.assertNotNull("missing command: " + name, meta);
                Assert.assertFalse(name + " should not require bootstrap", meta.requiresBootstrap());
            }
        });
    }

    @Test
    public void diskWritingCommandsDeclareWritesDiskCapability() throws Exception {
        withDescriptors((descriptors) -> {
            Map<String, CommandMeta> metaByName = byName(descriptors);
            for (String name : new String[]{"heapdump", "dump", "mc"}) {
                CommandMeta meta = metaByName.get(name);
                Assert.assertNotNull("missing command: " + name, meta);
                Assert.assertTrue(name + " should declare disk writes",
                    meta.hasCapability(CommandCapability.WRITES_DISK));
            }
        });
    }

    private static Map<String, CommandMeta> byName(Collection<CommandDescriptor> descriptors) {
        Map<String, CommandMeta> out = new HashMap<>();
        for (CommandDescriptor descriptor : descriptors) {
            out.put(descriptor.getName(), descriptor.getMeta());
        }
        return out;
    }

    private static void withDescriptors(DescriptorConsumer consumer) throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (
            AuditLogger auditLogger = new AuditLogger(config);
            AuthenticationManager authenticationManager = new AuthenticationManager(config, auditLogger);
            DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger);
            PerformanceOptimizer optimizer = new PerformanceOptimizer(config)
        ) {
            MetricsCollector metricsCollector = new MetricsCollector(config);
            SleuthSpyDispatcher spyDispatcher = new SleuthSpyDispatcher();
            try {
                CommandProviderContext context = new CommandProviderContext(
                    fakeInstrumentation(),
                    new SleuthClassFileTransformer(config),
                    metricsCollector,
                    config,
                    auditLogger,
                    null,
                    authenticationManager,
                    dangerousConfirm,
                    new JobManager(),
                    new VmToolSessionRegistry(spyDispatcher),
                    optimizer,
                    spyDispatcher,
                    new EnhancementSessionRegistry()
                );
                consumer.accept(new BuiltinCommandProvider().getCommandDescriptors(context));
            } finally {
                metricsCollector.shutdown();
            }
        }
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, args) -> {
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return false;
                }
                if (returnType == Integer.TYPE) {
                    return 0;
                }
                if (returnType == Long.TYPE) {
                    return 0L;
                }
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                return null;
            }
        );
    }

    private interface DescriptorConsumer {
        void accept(Collection<CommandDescriptor> descriptors) throws Exception;
    }
}
```

- [ ] **Step 2: Run the failing built-in metadata test**

Run:

```bash
mvn -pl core -Dtest=BuiltinCommandCapabilityTest test
```

Expected: assertion failures because built-in commands do not yet declare the new metadata.

- [ ] **Step 3: Add imports and helper metadata methods**

Modify `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`.

Add imports:

```java
import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.foundation.security.CommandCapability;
```

Add helper methods near the existing `add(...)` helper:

```java
    private static CommandMeta instrumentationStreamMeta() {
        return CommandMeta.operator(false, true)
            .requiresBootstrap(BootstrapBridge.SPY_API)
            .withCapability(CommandCapability.LONG_RUNNING);
    }

    private static CommandMeta writesDisk(CommandMeta meta) {
        return meta.withCapability(CommandCapability.WRITES_DISK);
    }
```

- [ ] **Step 4: Annotate stream instrumentation commands**

In `BuiltinCommandProvider`, replace these metadata expressions:

```java
CommandMeta.operator(false, true)
```

for `watch`, `trace`, `tt`, `monitor`, and `stack` with:

```java
instrumentationStreamMeta()
```

- [ ] **Step 5: Annotate `vmtool`**

In the `vmtool` descriptor metadata chain, add bootstrap and long-running capabilities:

```java
            CommandMeta.operator(false, false)
                .requiresBootstrap(BootstrapBridge.SPY_API)
                .withCapability(CommandCapability.LONG_RUNNING)
                .withImpact(CommandMeta.ImpactLevel.MEDIUM)
                .withRateLimit(10)
                .withSubcommandRole("invoke", UserRole.ADMIN)
                .withSubcommandRole("invoke-static", UserRole.ADMIN)
                .withSubcommandRole("invokestatic", UserRole.ADMIN)
```

- [ ] **Step 6: Annotate disk-writing commands**

Wrap these metadata expressions:

For `mc`:

```java
writesDisk(CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(3))
```

For `heapdump`:

```java
writesDisk(CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(2))
```

For `dump`:

```java
writesDisk(CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(5))
```

- [ ] **Step 7: Run the built-in metadata test**

Run:

```bash
mvn -pl core -Dtest=BuiltinCommandCapabilityTest test
```

Expected: PASS.

- [ ] **Step 8: Commit built-in metadata annotations**

```bash
git add core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java \
  core/src/test/java/com/javasleuth/command/BuiltinCommandCapabilityTest.java
git commit -m "feat: declare built-in command capabilities"
```

---

### Task 4: Document Provider Metadata

**Files:**
- Create: `docs/dev/command-provider-metadata.md`
- Modify: `docs/index.md`

- [ ] **Step 1: Add provider metadata documentation**

Create `docs/dev/command-provider-metadata.md`:

````markdown
# Command Provider Metadata

Command providers must publish command behavior through `CommandMeta`. The metadata is the single source used by authorization, confirmation, caching, streaming, rate limiting, and precheck gates.

## Required Bootstrap Classes

Commands that install bytecode enhancers or otherwise inject calls to bootstrap-side classes must declare the required bootstrap-visible class:

```java
CommandMeta.operator(false, true)
    .requiresBootstrap("com.javasleuth.bootstrap.spy.SleuthSpyAPI");
```

The precheck pipeline rejects non-help invocations when the class is unavailable. The transformer still performs its own runtime check when an enhancer is added.

## Capabilities

Use `CommandCapability` to describe operational traits:

```java
CommandMeta.operator(false, true)
    .requiresBootstrap("com.javasleuth.bootstrap.spy.SleuthSpyAPI")
    .withCapability(CommandCapability.LONG_RUNNING);
```

Available capabilities:

- `USES_INSTRUMENTATION`: command touches loaded classes, retransforms classes, or installs enhancers.
- `LONG_RUNNING`: command can run beyond a quick request-response interaction.
- `WRITES_DISK`: command writes files to disk.

`requiresBootstrap(...)` automatically adds `USES_INSTRUMENTATION`.

## Compatibility

Existing constructors and factories remain valid:

```java
CommandMeta.viewer(true, false);
CommandMeta.operator(false, true);
CommandMeta.admin(false, false);
```

Add capability metadata when the command needs the corresponding precheck or policy behavior. Do not duplicate command-name checks outside metadata.
````

- [ ] **Step 2: Link the new doc from `docs/index.md`**

In `docs/index.md`, under the `开发` section, add:

```markdown
  - `docs/dev/command-provider-metadata.md` - 命令提供者元数据与能力声明
```

- [ ] **Step 3: Commit documentation**

```bash
git add docs/dev/command-provider-metadata.md docs/index.md
git commit -m "docs: describe command capability metadata"
```

---

### Task 5: Final Verification

**Files:**
- Verify all modified production, test, and docs files.

- [ ] **Step 1: Run focused tests**

Run:

```bash
mvn -pl core -Dtest=CommandMetaCapabilityTest,PrecheckPipelineBootstrapCapabilityTest,BuiltinCommandCapabilityTest test
```

Expected: PASS.

- [ ] **Step 2: Run core module tests**

Run:

```bash
mvn -pl core test
```

Expected: PASS.

- [ ] **Step 3: Run foundation module verification**

Run:

```bash
mvn -pl foundation test
```

Expected: PASS or no tests run successfully. The module must compile and maintain the no-dependency enforcer rule.

- [ ] **Step 4: Check for lingering hard-coded bridge command mapping**

Run:

```bash
rg -n "requiredBootstrapClassForCommand|case \"watch\"|case \"trace\"|case \"monitor\"|case \"tt\"|case \"stack\"|case \"vmtool\"" core/src/main/java/com/javasleuth/core/command/pipeline/PrecheckPipeline.java
```

Expected: no output.

- [ ] **Step 5: Check git diff**

Run:

```bash
git status --short
git diff --check
```

Expected: `git diff --check` has no output. `git status --short` shows only the intended files if commits were not made task-by-task, or no output if all task commits were made.

---

## Self-Review Notes

- Spec coverage: Tasks cover `CommandCapability`, `CommandMeta` bootstrap metadata, metadata-driven `PrecheckPipeline`, built-in command annotations, plugin/provider docs, and verification.
- Scope: The plan intentionally keeps subcommand-level predicates deferred, matching the approved spec.
- Compatibility: Existing `CommandMeta` constructors and factories stay source-compatible.
- Safety: Transformer-level `BootstrapDependentEnhancer` checks are not modified.
