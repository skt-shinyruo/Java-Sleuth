# Enhancement Session Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an attach-scope `EnhancementSessionRegistry` and route all Java-Sleuth bytecode enhancement sessions through it for reset, status, client disconnect, stop, and detach.

**Architecture:** Add a small lifecycle registry under `core.enhancement.session`, create one instance per attach in `AttachSessionContext`, and inject it through the existing command provider context. Each instrumentation command keeps its command-specific queue/listener/session data, but every active enhancer session is represented by one registry handle whose close callback performs command-owned cleanup.

**Tech Stack:** Java 8, Maven multi-module, JUnit 4, `ConcurrentHashMap`, `AtomicBoolean`, Java Instrumentation API, existing `SleuthSpyDispatcher`, existing `SleuthClassFileTransformer`.

---

## File Structure

Create registry package:

- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionKind.java`: enum for `WATCH`, `TRACE`, `MONITOR`, `STACK`, `TT`, `VMTOOL`, `OTHER`.
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionCloser.java`: functional close callback.
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionHandle.java`: idempotent close token returned by registry.
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionDescriptor.java`: immutable metadata supplied at registration.
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionSnapshot.java`: immutable active-session view for status/tests.
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionCloseSummary.java`: close-all summary for reset/detach output.
- `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionRegistry.java`: attach-scope registry.

Modify composition:

- `core/src/main/java/com/javasleuth/core/agent/runtime/AttachSessionContext.java`: create, expose, and close registry.
- `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactoryRequest.java`: carry a runtime-supplied registry or let the factory create one.
- `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java`: create default registry when absent and pass to provider context.
- `core/src/main/java/com/javasleuth/core/command/CommandProviderContext.java`: expose `requireEnhancementSessionRegistry()`.
- `core/src/main/java/com/javasleuth/core/command/CommandProcessorComponents.java`: expose registry for tests if needed.
- `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`: inject registry into affected commands.

Modify lifecycle commands:

- `core/src/main/java/com/javasleuth/core/command/impl/ResetCommand.java`: close registry sessions before fallback cleanup.
- `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`: display registry counts.

Modify instrumentation commands:

- `core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/StackCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java`
- `core/src/main/java/com/javasleuth/core/command/impl/TtCommand.java`
- `core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java`
- `core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java`

Keep `core/src/main/java/com/javasleuth/core/vmtool/VmToolSessionRegistry.java` as the vmtool tracker owner; do not inject the unified registry into that class.

Create and modify tests:

- Create `core/src/test/java/com/javasleuth/core/enhancement/session/EnhancementSessionRegistryTest.java`
- Create `core/src/test/java/com/javasleuth/command/EnhancementSessionResetTest.java`
- Modify `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java`
- Modify `core/src/test/java/com/javasleuth/command/ListenerModeRequirementTest.java`
- Modify `core/src/test/java/com/javasleuth/core/agent/runtime/AttachSessionContextTest.java`

Modify docs:

- `docs/tutorial/command-instrumentation-and-rollback.md`
- `docs/usage/commands.md`
- `docs/usage/troubleshooting.md`

---

### Task 1: Add Registry Types and Unit Tests

**Files:**
- Create: `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionKind.java`
- Create: `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionCloser.java`
- Create: `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionHandle.java`
- Create: `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionDescriptor.java`
- Create: `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionSnapshot.java`
- Create: `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionCloseSummary.java`
- Create: `core/src/main/java/com/javasleuth/core/enhancement/session/EnhancementSessionRegistry.java`
- Create: `core/src/test/java/com/javasleuth/core/enhancement/session/EnhancementSessionRegistryTest.java`

- [ ] **Step 1: Write the failing registry test**

Create `EnhancementSessionRegistryTest.java` with these tests:

```java
package com.javasleuth.core.enhancement.session;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class EnhancementSessionRegistryTest {

    @Test
    public void registerListCountAndCloseById() {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger closed = new AtomicInteger(0);

        EnhancementSessionHandle handle = registry.register(
            EnhancementSessionDescriptor.builder("watch-1", EnhancementSessionKind.WATCH)
                .withClientId("client-a")
                .withClientSessionId("session-a")
                .withCommandName("watch")
                .withClassPattern("com.example.Demo")
                .withMethodPattern("run")
                .withTargetClassNames(Collections.singletonList("com.example.Demo"))
                .withLoaderIds(Collections.singletonList(123))
                .withDetails("count=1")
                .build(),
            reason -> closed.incrementAndGet()
        );

        Assert.assertEquals("watch-1", handle.getSessionId());
        Assert.assertEquals(1, registry.size());
        Assert.assertEquals(1, registry.list().size());
        Assert.assertEquals("client-a", registry.list().get(0).getClientId());

        Map<EnhancementSessionKind, Integer> counts = registry.countByKind();
        Assert.assertEquals(Integer.valueOf(1), counts.get(EnhancementSessionKind.WATCH));
        Assert.assertEquals(Integer.valueOf(0), counts.get(EnhancementSessionKind.TRACE));

        Assert.assertTrue(registry.close("watch-1", "test"));
        Assert.assertFalse(registry.close("watch-1", "second"));
        Assert.assertTrue(handle.isClosed());
        Assert.assertEquals(1, closed.get());
        Assert.assertEquals(0, registry.size());
    }

    @Test
    public void closeByClientOnlyClosesMatchingSessions() {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger clientAClosed = new AtomicInteger(0);
        AtomicInteger clientBClosed = new AtomicInteger(0);

        registry.register(
            EnhancementSessionDescriptor.builder("trace-1", EnhancementSessionKind.TRACE)
                .withClientId("client-a")
                .withCommandName("trace")
                .build(),
            reason -> clientAClosed.incrementAndGet()
        );
        registry.register(
            EnhancementSessionDescriptor.builder("tt-1", EnhancementSessionKind.TT)
                .withClientId("client-b")
                .withCommandName("tt")
                .build(),
            reason -> clientBClosed.incrementAndGet()
        );

        Assert.assertEquals(1, registry.closeByClient("client-a", "disconnect"));
        Assert.assertEquals(1, clientAClosed.get());
        Assert.assertEquals(0, clientBClosed.get());
        Assert.assertEquals(1, registry.size());
        Assert.assertEquals("tt-1", registry.list().get(0).getSessionId());
    }

    @Test
    public void closeAllIsIdempotentAndRecordsFailures() {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger closed = new AtomicInteger(0);

        registry.register(
            EnhancementSessionDescriptor.builder("stack-1", EnhancementSessionKind.STACK)
                .withTargetClassNames(Arrays.asList("a.A", "b.B"))
                .build(),
            reason -> closed.incrementAndGet()
        );
        registry.register(
            EnhancementSessionDescriptor.builder("monitor-1", EnhancementSessionKind.MONITOR)
                .build(),
            reason -> {
                throw new IllegalStateException("boom");
            }
        );

        EnhancementSessionCloseSummary summary = registry.closeAll("reset");

        Assert.assertEquals(2, summary.getTotal());
        Assert.assertEquals(1, summary.getClosed());
        Assert.assertEquals(1, summary.getFailed());
        Assert.assertEquals(1, closed.get());
        Assert.assertEquals(0, registry.size());
        Assert.assertTrue(summary.getFailureMessages().get("monitor-1").contains("boom"));

        EnhancementSessionCloseSummary second = registry.closeAll("reset-again");
        Assert.assertEquals(0, second.getTotal());
        Assert.assertEquals(0, second.getClosed());
        Assert.assertEquals(0, second.getFailed());
    }

    @Test
    public void duplicateRegistrationClosesOldHandle() {
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger oldClosed = new AtomicInteger(0);
        AtomicInteger newClosed = new AtomicInteger(0);

        registry.register(
            EnhancementSessionDescriptor.builder("same-id", EnhancementSessionKind.WATCH).build(),
            reason -> oldClosed.incrementAndGet()
        );
        registry.register(
            EnhancementSessionDescriptor.builder("same-id", EnhancementSessionKind.TRACE).build(),
            reason -> newClosed.incrementAndGet()
        );

        Assert.assertEquals(1, oldClosed.get());
        Assert.assertEquals(0, newClosed.get());
        Assert.assertEquals(Integer.valueOf(0), registry.countByKind().get(EnhancementSessionKind.WATCH));
        Assert.assertEquals(Integer.valueOf(1), registry.countByKind().get(EnhancementSessionKind.TRACE));
    }
}
```

- [ ] **Step 2: Run the registry test and verify it fails**

Run:

```bash
mvn -pl core -Dtest=EnhancementSessionRegistryTest test
```

Expected: FAIL at compilation with missing `EnhancementSessionRegistry` and related session types.

- [ ] **Step 3: Add the registry enum and callback interfaces**

Create `EnhancementSessionKind.java`:

```java
package com.javasleuth.core.enhancement.session;

public enum EnhancementSessionKind {
    WATCH,
    TRACE,
    MONITOR,
    STACK,
    TT,
    VMTOOL,
    OTHER
}
```

Create `EnhancementSessionCloser.java`:

```java
package com.javasleuth.core.enhancement.session;

@FunctionalInterface
public interface EnhancementSessionCloser {
    void close(String reason) throws Exception;
}
```

Create `EnhancementSessionHandle.java`:

```java
package com.javasleuth.core.enhancement.session;

public interface EnhancementSessionHandle extends AutoCloseable {
    String getSessionId();
    EnhancementSessionKind getKind();
    boolean isClosed();
    void close(String reason);

    @Override
    default void close() {
        close("close");
    }
}
```

- [ ] **Step 4: Add descriptor, snapshot, and close summary value types**

Create `EnhancementSessionDescriptor.java`:

```java
package com.javasleuth.core.enhancement.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EnhancementSessionDescriptor {
    private final String sessionId;
    private final EnhancementSessionKind kind;
    private final String clientId;
    private final String clientSessionId;
    private final String commandName;
    private final String classPattern;
    private final String methodPattern;
    private final List<String> targetClassNames;
    private final List<Integer> loaderIds;
    private final String backgroundJobId;
    private final long createdAtMs;
    private final String details;

    private EnhancementSessionDescriptor(Builder builder) {
        this.sessionId = normalizeRequired(builder.sessionId, "sessionId");
        this.kind = builder.kind != null ? builder.kind : EnhancementSessionKind.OTHER;
        this.clientId = trimToNull(builder.clientId);
        this.clientSessionId = trimToNull(builder.clientSessionId);
        this.commandName = trimToNull(builder.commandName);
        this.classPattern = trimToNull(builder.classPattern);
        this.methodPattern = trimToNull(builder.methodPattern);
        this.targetClassNames = immutableCopy(builder.targetClassNames);
        this.loaderIds = immutableCopy(builder.loaderIds);
        this.backgroundJobId = trimToNull(builder.backgroundJobId);
        this.createdAtMs = builder.createdAtMs > 0 ? builder.createdAtMs : System.currentTimeMillis();
        this.details = trimToNull(builder.details);
    }

    public static Builder builder(String sessionId, EnhancementSessionKind kind) {
        return new Builder(sessionId, kind);
    }

    public String getSessionId() { return sessionId; }
    public EnhancementSessionKind getKind() { return kind; }
    public String getClientId() { return clientId; }
    public String getClientSessionId() { return clientSessionId; }
    public String getCommandName() { return commandName; }
    public String getClassPattern() { return classPattern; }
    public String getMethodPattern() { return methodPattern; }
    public List<String> getTargetClassNames() { return targetClassNames; }
    public List<Integer> getLoaderIds() { return loaderIds; }
    public String getBackgroundJobId() { return backgroundJobId; }
    public long getCreatedAtMs() { return createdAtMs; }
    public String getDetails() { return details; }

    private static String normalizeRequired(String value, String name) {
        String v = trimToNull(value);
        if (v == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return v;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    public static final class Builder {
        private final String sessionId;
        private final EnhancementSessionKind kind;
        private String clientId;
        private String clientSessionId;
        private String commandName;
        private String classPattern;
        private String methodPattern;
        private List<String> targetClassNames = Collections.emptyList();
        private List<Integer> loaderIds = Collections.emptyList();
        private String backgroundJobId;
        private long createdAtMs;
        private String details;

        private Builder(String sessionId, EnhancementSessionKind kind) {
            this.sessionId = sessionId;
            this.kind = kind;
        }

        public Builder withClientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder withClientSessionId(String clientSessionId) {
            this.clientSessionId = clientSessionId;
            return this;
        }

        public Builder withCommandName(String commandName) {
            this.commandName = commandName;
            return this;
        }

        public Builder withClassPattern(String classPattern) {
            this.classPattern = classPattern;
            return this;
        }

        public Builder withMethodPattern(String methodPattern) {
            this.methodPattern = methodPattern;
            return this;
        }

        public Builder withTargetClassNames(List<String> targetClassNames) {
            this.targetClassNames = targetClassNames != null ? targetClassNames : Collections.<String>emptyList();
            return this;
        }

        public Builder withLoaderIds(List<Integer> loaderIds) {
            this.loaderIds = loaderIds != null ? loaderIds : Collections.<Integer>emptyList();
            return this;
        }

        public Builder withBackgroundJobId(String backgroundJobId) {
            this.backgroundJobId = backgroundJobId;
            return this;
        }

        public Builder withCreatedAtMs(long createdAtMs) {
            this.createdAtMs = createdAtMs;
            return this;
        }

        public Builder withDetails(String details) {
            this.details = details;
            return this;
        }

        public EnhancementSessionDescriptor build() {
            return new EnhancementSessionDescriptor(this);
        }
    }
}
```

Create `EnhancementSessionSnapshot.java`:

```java
package com.javasleuth.core.enhancement.session;

import java.util.List;

public final class EnhancementSessionSnapshot {
    private final EnhancementSessionDescriptor descriptor;

    EnhancementSessionSnapshot(EnhancementSessionDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor is required");
        }
        this.descriptor = descriptor;
    }

    public String getSessionId() { return descriptor.getSessionId(); }
    public EnhancementSessionKind getKind() { return descriptor.getKind(); }
    public String getClientId() { return descriptor.getClientId(); }
    public String getClientSessionId() { return descriptor.getClientSessionId(); }
    public String getCommandName() { return descriptor.getCommandName(); }
    public String getClassPattern() { return descriptor.getClassPattern(); }
    public String getMethodPattern() { return descriptor.getMethodPattern(); }
    public List<String> getTargetClassNames() { return descriptor.getTargetClassNames(); }
    public List<Integer> getLoaderIds() { return descriptor.getLoaderIds(); }
    public String getBackgroundJobId() { return descriptor.getBackgroundJobId(); }
    public long getCreatedAtMs() { return descriptor.getCreatedAtMs(); }
    public String getDetails() { return descriptor.getDetails(); }
}
```

Create `EnhancementSessionCloseSummary.java`:

```java
package com.javasleuth.core.enhancement.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EnhancementSessionCloseSummary {
    private final int total;
    private final int closed;
    private final int missing;
    private final int failed;
    private final Map<String, String> failureMessages;

    EnhancementSessionCloseSummary(int total, int closed, int missing, int failed, Map<String, String> failureMessages) {
        this.total = Math.max(0, total);
        this.closed = Math.max(0, closed);
        this.missing = Math.max(0, missing);
        this.failed = Math.max(0, failed);
        this.failureMessages = failureMessages == null || failureMessages.isEmpty()
            ? Collections.<String, String>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, String>(failureMessages));
    }

    public int getTotal() { return total; }
    public int getClosed() { return closed; }
    public int getMissing() { return missing; }
    public int getFailed() { return failed; }
    public Map<String, String> getFailureMessages() { return failureMessages; }
}
```

- [ ] **Step 5: Implement the registry**

Create `EnhancementSessionRegistry.java`:

```java
package com.javasleuth.core.enhancement.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EnhancementSessionRegistry implements AutoCloseable {
    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();

    public EnhancementSessionHandle register(EnhancementSessionDescriptor descriptor, EnhancementSessionCloser closer) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor is required");
        }
        if (closer == null) {
            throw new IllegalArgumentException("closer is required");
        }
        Entry next = new Entry(descriptor, closer);
        Entry old = sessions.put(descriptor.getSessionId(), next);
        if (old != null) {
            old.closeInternal("replaced");
        }
        return next;
    }

    public boolean close(String sessionId, String reason) {
        CloseResult result = closeOne(sessionId, reason);
        return result.selected && !result.failed;
    }

    public int closeByClient(String clientId, String reason) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (Entry entry : new ArrayList<Entry>(sessions.values())) {
            if (entry == null || entry.descriptor == null) {
                continue;
            }
            if (clientId.equals(entry.descriptor.getClientId())) {
                CloseResult result = closeOne(entry.getSessionId(), reason);
                if (result.selected && !result.failed) {
                    closed++;
                }
            }
        }
        return closed;
    }

    public EnhancementSessionCloseSummary closeAll(String reason) {
        List<String> ids = new ArrayList<String>(sessions.keySet());
        int closed = 0;
        int missing = 0;
        int failed = 0;
        Map<String, String> failures = new LinkedHashMap<String, String>();
        for (String id : ids) {
            CloseResult result = closeOne(id, reason);
            if (!result.selected) {
                missing++;
            } else if (result.failed) {
                failed++;
                failures.put(id, result.failureMessage);
            } else {
                closed++;
            }
        }
        return new EnhancementSessionCloseSummary(ids.size(), closed, missing, failed, failures);
    }

    public List<EnhancementSessionSnapshot> list() {
        List<Entry> entries = new ArrayList<Entry>(sessions.values());
        Collections.sort(entries, (a, b) -> Long.compare(a.descriptor.getCreatedAtMs(), b.descriptor.getCreatedAtMs()));
        List<EnhancementSessionSnapshot> out = new ArrayList<EnhancementSessionSnapshot>();
        for (Entry entry : entries) {
            out.add(new EnhancementSessionSnapshot(entry.descriptor));
        }
        return Collections.unmodifiableList(out);
    }

    public Map<EnhancementSessionKind, Integer> countByKind() {
        EnumMap<EnhancementSessionKind, Integer> counts = new EnumMap<EnhancementSessionKind, Integer>(EnhancementSessionKind.class);
        for (EnhancementSessionKind kind : EnhancementSessionKind.values()) {
            counts.put(kind, 0);
        }
        for (Entry entry : sessions.values()) {
            EnhancementSessionKind kind = entry != null && entry.descriptor != null
                ? entry.descriptor.getKind()
                : EnhancementSessionKind.OTHER;
            counts.put(kind, counts.get(kind) + 1);
        }
        return Collections.unmodifiableMap(counts);
    }

    public int size() {
        return sessions.size();
    }

    @Override
    public void close() {
        closeAll("close");
    }

    private CloseResult closeOne(String sessionId, String reason) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return CloseResult.missing();
        }
        Entry entry = sessions.remove(sessionId.trim());
        if (entry == null) {
            return CloseResult.missing();
        }
        return entry.closeInternal(reason);
    }

    private static final class CloseResult {
        private final boolean selected;
        private final boolean failed;
        private final String failureMessage;

        private CloseResult(boolean selected, boolean failed, String failureMessage) {
            this.selected = selected;
            this.failed = failed;
            this.failureMessage = failureMessage;
        }

        private static CloseResult closed() {
            return new CloseResult(true, false, null);
        }

        private static CloseResult failed(Throwable t) {
            String msg = t == null ? "unknown" : t.getClass().getName() + ": " + t.getMessage();
            return new CloseResult(true, true, msg);
        }

        private static CloseResult missing() {
            return new CloseResult(false, false, null);
        }
    }

    private final class Entry implements EnhancementSessionHandle {
        private final EnhancementSessionDescriptor descriptor;
        private final EnhancementSessionCloser closer;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Entry(EnhancementSessionDescriptor descriptor, EnhancementSessionCloser closer) {
            this.descriptor = descriptor;
            this.closer = closer;
        }

        @Override
        public String getSessionId() {
            return descriptor.getSessionId();
        }

        @Override
        public EnhancementSessionKind getKind() {
            return descriptor.getKind();
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close(String reason) {
            EnhancementSessionRegistry.this.close(getSessionId(), reason);
        }

        private CloseResult closeInternal(String reason) {
            if (!closed.compareAndSet(false, true)) {
                return CloseResult.closed();
            }
            try {
                closer.close(reason);
                return CloseResult.closed();
            } catch (Throwable t) {
                return CloseResult.failed(t);
            }
        }
    }
}
```

- [ ] **Step 6: Run registry tests**

Run:

```bash
mvn -pl core -Dtest=EnhancementSessionRegistryTest test
```

Expected: PASS.

- [ ] **Step 7: Commit registry foundation**

```bash
git add core/src/main/java/com/javasleuth/core/enhancement/session core/src/test/java/com/javasleuth/core/enhancement/session/EnhancementSessionRegistryTest.java
git commit -m "feat: add enhancement session registry"
```

---

### Task 2: Wire the Registry Through Attach-Scope Composition

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/agent/runtime/AttachSessionContext.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactoryRequest.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProviderContext.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/CommandProcessorComponents.java`
- Modify: `core/src/test/java/com/javasleuth/core/agent/runtime/AttachSessionContextTest.java`

- [ ] **Step 1: Add failing attach context test**

In `AttachSessionContextTest.create_providesAttachScopedRuntimeResources_andCloseIsIdempotent`, add:

```java
Assert.assertNotNull(session.getEnhancementSessionRegistry());
```

Add this test method to the same class:

```java
@Test
public void close_clearsEnhancementSessionRegistry() {
    AttachSessionContext session = AttachSessionContext.create(fakeInstrumentation(), () -> {});
    try {
        session.getEnhancementSessionRegistry().register(
            com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor
                .builder("attach-close-watch", com.javasleuth.core.enhancement.session.EnhancementSessionKind.WATCH)
                .build(),
            reason -> {}
        );
        Assert.assertEquals(1, session.getEnhancementSessionRegistry().size());
    } finally {
        session.close();
    }

    Assert.assertEquals(0, session.getEnhancementSessionRegistry().size());
}
```

- [ ] **Step 2: Run attach context test and verify it fails**

Run:

```bash
mvn -pl core -Dtest=AttachSessionContextTest test
```

Expected: FAIL at compilation because `getEnhancementSessionRegistry()` does not exist.

- [ ] **Step 3: Add registry field to `CommandProcessorFactoryRequest`**

Add import:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
```

Add field:

```java
private final EnhancementSessionRegistry enhancementSessionRegistry;
```

In constructor:

```java
this.enhancementSessionRegistry = builder.enhancementSessionRegistry;
```

Add getter:

```java
public EnhancementSessionRegistry getEnhancementSessionRegistry() {
    return enhancementSessionRegistry;
}
```

Add builder field and method:

```java
private EnhancementSessionRegistry enhancementSessionRegistry;

public Builder withEnhancementSessionRegistry(EnhancementSessionRegistry enhancementSessionRegistry) {
    this.enhancementSessionRegistry = enhancementSessionRegistry;
    return this;
}
```

- [ ] **Step 4: Add registry to `CommandProviderContext`**

Add import and field:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;

private final EnhancementSessionRegistry enhancementSessionRegistry;
```

Add constructor parameter after `VmToolSessionRegistry vmToolSessionRegistry`:

```java
EnhancementSessionRegistry enhancementSessionRegistry,
```

Set field:

```java
this.enhancementSessionRegistry = enhancementSessionRegistry;
```

Add getters:

```java
public EnhancementSessionRegistry getEnhancementSessionRegistry() {
    return enhancementSessionRegistry;
}

public EnhancementSessionRegistry requireEnhancementSessionRegistry() {
    return requireNonNull(enhancementSessionRegistry, "enhancementSessionRegistry");
}
```

- [ ] **Step 5: Add registry to `CommandProcessorComponents`**

Add import:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
```

Add field, constructor parameter, assignment, and getter:

```java
private final EnhancementSessionRegistry enhancementSessionRegistry;

public EnhancementSessionRegistry getEnhancementSessionRegistry() {
    return enhancementSessionRegistry;
}
```

Place the field near `ClientSessionRegistry`, `JobManager`, and `CommandRegistry` so ownership remains clear.

- [ ] **Step 6: Create default registry in `CommandProcessorFactory`**

In `createComponents(...)`, add:

```java
boolean ownsEnhancementSessionRegistry = request.getEnhancementSessionRegistry() == null;
com.javasleuth.core.enhancement.session.EnhancementSessionRegistry esr =
    request.getEnhancementSessionRegistry() != null
        ? request.getEnhancementSessionRegistry()
        : new com.javasleuth.core.enhancement.session.EnhancementSessionRegistry();
```

Pass `esr` to `CommandProviderContext` after `vmsr`:

```java
vmsr,
esr,
perf,
dispatcher
```

When owned resources are built, include:

```java
if (ownsEnhancementSessionRegistry) {
    closeables.add(() -> esr.closeAll("shutdown"));
}
```

Pass `esr` to `CommandProcessorComponents`.

- [ ] **Step 7: Create registry in `AttachSessionContext`**

Add import:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
```

Add field:

```java
private final EnhancementSessionRegistry enhancementSessionRegistry;
```

Add constructor parameter after `VmToolSessionRegistry vmToolSessionRegistry` and assign it.

In `create(...)`, add local:

```java
EnhancementSessionRegistry enhancementSessionRegistry = null;
```

Create it after `vmToolSessionRegistry`:

```java
enhancementSessionRegistry = new EnhancementSessionRegistry();
```

Pass it to the factory request:

```java
.withEnhancementSessionRegistry(enhancementSessionRegistry)
```

Pass it into the `AttachSessionContext` constructor.

Add getter:

```java
public EnhancementSessionRegistry getEnhancementSessionRegistry() {
    return enhancementSessionRegistry;
}
```

- [ ] **Step 8: Close registry during attach shutdown and startup failure**

Add helper in `AttachSessionContext`:

```java
private static void shutdownEnhancementSessionsBestEffort(
    EnhancementSessionRegistry registry,
    String reason
) {
    try {
        if (registry != null) {
            registry.closeAll(reason);
        }
    } catch (Exception ignore) {
        // best-effort
    }
}
```

Call this before job/client/vmtool shutdown in `shutdownAttachResourcesBestEffort(...)`.

Update `shutdownAttachResourcesBestEffort(...)` signature to include `EnhancementSessionRegistry enhancementSessionRegistry` and call:

```java
shutdownEnhancementSessionsBestEffort(enhancementSessionRegistry, "shutdown");
shutdownJobManagerBestEffort(jobManager, "shutdown");
shutdownClientSessionsBestEffort(clientSessionRegistry, "shutdown");
shutdownVmToolSessionsBestEffort(instrumentation, transformer, vmToolSessionRegistry, "shutdown");
AgentGlobalState.resetBootstrapAttachStateBestEffort();
```

In `cleanupStartupFailure(...)`, call:

```java
shutdownEnhancementSessionsBestEffort(enhancementSessionRegistry, "startup_failed");
```

before vmtool/client fallback shutdown.

Move dispatcher destruction in `close()` so registry close callbacks can unregister listeners before the dispatcher is cleared. The close body should follow this order:

```java
Instrumentation inst = instrumentation;
SleuthClassFileTransformer tx = transformer;
Set<String> enhanced = snapshotEnhancedClassNames(tx);

shutdownCommandProcessorBestEffort(commandProcessor);
shutdownAttachResourcesBestEffort(inst, tx, jobManager, clientSessionRegistry, vmToolSessionRegistry, enhancementSessionRegistry);
destroySpyBestEffort(spyDispatcher);
removeEnhancersBestEffort(tx);
rollbackEnhancedClassesBestEffort(inst, enhanced);
removeTransformerBestEffort(inst, tx);
joinCommandThreadBestEffort(commandThread);
closeServicesBestEffort(services);
```

- [ ] **Step 9: Run attach context tests**

Run:

```bash
mvn -pl core -Dtest=AttachSessionContextTest test
```

Expected: PASS.

- [ ] **Step 10: Commit composition wiring**

```bash
git add core/src/main/java/com/javasleuth/core/agent/runtime/AttachSessionContext.java core/src/main/java/com/javasleuth/core/command/CommandProcessorFactoryRequest.java core/src/main/java/com/javasleuth/core/command/CommandProcessorFactory.java core/src/main/java/com/javasleuth/core/command/CommandProviderContext.java core/src/main/java/com/javasleuth/core/command/CommandProcessorComponents.java core/src/test/java/com/javasleuth/core/agent/runtime/AttachSessionContextTest.java
git commit -m "feat: wire enhancement sessions into attach scope"
```

---

### Task 3: Integrate Reset and Status with Registry

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/ResetCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- Create: `core/src/test/java/com/javasleuth/command/EnhancementSessionResetTest.java`

- [ ] **Step 1: Write reset and status tests**

Create `EnhancementSessionResetTest.java`:

```java
package com.javasleuth.command;

import com.javasleuth.core.agent.runtime.AgentGlobalState;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.impl.ResetCommand;
import com.javasleuth.core.command.impl.StatusCommand;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

public class EnhancementSessionResetTest {

    @After
    public void tearDown() {
        AgentGlobalState.resetBootstrapAttachStateBestEffort();
    }

    @Test
    public void resetClosesEnhancementSessionsBeforeTransformerFallback() {
        ProductionConfig config = ProductionConfig.createDefault();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        AtomicInteger closed = new AtomicInteger(0);
        registry.register(
            EnhancementSessionDescriptor.builder("watch-reset", EnhancementSessionKind.WATCH)
                .withCommandName("watch")
                .build(),
            reason -> closed.incrementAndGet()
        );

        ResetCommand command = new ResetCommand(
            fakeInstrumentation(),
            transformer,
            new JobManager(),
            new VmToolSessionRegistry(new SleuthSpyDispatcher()),
            registry
        );

        String output = command.execute(new String[] {"reset"});

        Assert.assertTrue(output.contains("enhancementSessions=1"));
        Assert.assertTrue(output.contains("enhancementClosed=1"));
        Assert.assertEquals(1, closed.get());
        Assert.assertEquals(0, registry.size());
    }

    @Test
    public void statusReportsEnhancementSessionCounts() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
        registry.register(
            EnhancementSessionDescriptor.builder("trace-status", EnhancementSessionKind.TRACE).build(),
            reason -> {}
        );
        registry.register(
            EnhancementSessionDescriptor.builder("vmtool-status", EnhancementSessionKind.VMTOOL).build(),
            reason -> {}
        );
        PerformanceOptimizer optimizer = new PerformanceOptimizer(config);
        try {
            StatusCommand command = new StatusCommand(
                fakeInstrumentation(),
                new MetricsCollector(config),
                new SleuthClassFileTransformer(config),
                config,
                optimizer,
                new SleuthSpyDispatcher(),
                registry
            );

            String output = command.execute(new String[] {"status"});

            Assert.assertTrue(output.contains("-- Enhancement Sessions --"));
            Assert.assertTrue(output.contains("Active Sessions: 2"));
            Assert.assertTrue(output.contains("Trace: 1"));
            Assert.assertTrue(output.contains("VmTool: 1"));
        } finally {
            optimizer.close();
        }
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return new Class<?>[0];
                }
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("isRedefineClassesSupported".equals(name) || "isRetransformClassesSupported".equals(name)) {
                    return true;
                }
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
}
```

- [ ] **Step 2: Run reset/status tests and verify they fail**

Run:

```bash
mvn -pl core -Dtest=EnhancementSessionResetTest test
```

Expected: FAIL at compilation because `ResetCommand` and `StatusCommand` constructors do not accept `EnhancementSessionRegistry`.

- [ ] **Step 3: Update `ResetCommand`**

Add imports:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionCloseSummary;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
```

Add field:

```java
private final EnhancementSessionRegistry enhancementSessionRegistry;
```

Change constructor to:

```java
public ResetCommand(
    Instrumentation instrumentation,
    SleuthClassFileTransformer transformer,
    JobManager jobManager,
    VmToolSessionRegistry vmToolSessionRegistry,
    EnhancementSessionRegistry enhancementSessionRegistry
) {
    this.instrumentation = instrumentation;
    this.transformer = transformer;
    if (jobManager == null) {
        throw new IllegalArgumentException("jobManager");
    }
    if (vmToolSessionRegistry == null) {
        throw new IllegalArgumentException("vmToolSessionRegistry");
    }
    if (enhancementSessionRegistry == null) {
        throw new IllegalArgumentException("enhancementSessionRegistry");
    }
    this.jobManager = jobManager;
    this.vmToolSessionRegistry = vmToolSessionRegistry;
    this.enhancementSessionRegistry = enhancementSessionRegistry;
}
```

In `execute`, call the registry after taking the enhanced class snapshot and before vmtool fallback:

```java
EnhancementSessionCloseSummary enhancementSummary = enhancementSessionRegistry.closeAll("reset");
```

Return:

```java
return "Reset done. jobsStopped=" + stoppedJobs +
    ", enhancementSessions=" + enhancementSummary.getTotal() +
    ", enhancementClosed=" + enhancementSummary.getClosed() +
    ", enhancementFailed=" + enhancementSummary.getFailed() +
    ", enhancedClasses=" + enhanced.size() +
    ", retransformed=" + retransformCount +
    ", skipped=" + skipped;
```

- [ ] **Step 4: Update `StatusCommand`**

Add imports:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
```

Add field:

```java
private final EnhancementSessionRegistry enhancementSessionRegistry;
```

Extend constructor with:

```java
EnhancementSessionRegistry enhancementSessionRegistry
```

Validate and assign:

```java
if (enhancementSessionRegistry == null) {
    throw new IllegalArgumentException("enhancementSessionRegistry");
}
this.enhancementSessionRegistry = enhancementSessionRegistry;
```

Add this section after the watch/trace observability block:

```java
status.append("\n-- Enhancement Sessions --\n");
Map<EnhancementSessionKind, Integer> enhancementCounts = enhancementSessionRegistry.countByKind();
status.append("Active Sessions: ").append(enhancementSessionRegistry.size()).append("\n");
status.append("Watch: ").append(enhancementCounts.get(EnhancementSessionKind.WATCH)).append("\n");
status.append("Trace: ").append(enhancementCounts.get(EnhancementSessionKind.TRACE)).append("\n");
status.append("Monitor: ").append(enhancementCounts.get(EnhancementSessionKind.MONITOR)).append("\n");
status.append("Stack: ").append(enhancementCounts.get(EnhancementSessionKind.STACK)).append("\n");
status.append("TT: ").append(enhancementCounts.get(EnhancementSessionKind.TT)).append("\n");
status.append("VmTool: ").append(enhancementCounts.get(EnhancementSessionKind.VMTOOL)).append("\n");
status.append("Other: ").append(enhancementCounts.get(EnhancementSessionKind.OTHER)).append("\n");
```

- [ ] **Step 5: Inject registry from `BuiltinCommandProvider`**

Update reset:

```java
new ResetCommand(
    instrumentation,
    context.requireTransformer(),
    context.requireJobManager(),
    context.requireVmToolSessionRegistry(),
    context.requireEnhancementSessionRegistry()
)
```

Update status:

```java
new StatusCommand(
    instrumentation,
    context.requireMetricsCollector(),
    context.requireTransformer(),
    context.requireConfig(),
    context.requirePerformanceOptimizer(),
    context.requireSpyDispatcher(),
    context.requireEnhancementSessionRegistry()
)
```

- [ ] **Step 6: Run reset/status tests**

Run:

```bash
mvn -pl core -Dtest=EnhancementSessionResetTest test
```

Expected: PASS.

- [ ] **Step 7: Commit reset/status integration**

```bash
git add core/src/main/java/com/javasleuth/core/command/impl/ResetCommand.java core/src/main/java/com/javasleuth/core/command/impl/StatusCommand.java core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java core/src/test/java/com/javasleuth/command/EnhancementSessionResetTest.java
git commit -m "feat: report and reset enhancement sessions"
```

---

### Task 4: Migrate Watch and Trace Sessions

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- Modify: `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java`
- Modify: `core/src/test/java/com/javasleuth/command/ListenerModeRequirementTest.java`

- [ ] **Step 1: Extend disconnect test for registry counts**

In `SessionCleanupOnDisconnectTest`, create one registry and pass it to watch/trace/tt constructors:

```java
EnhancementSessionRegistry enhancementRegistry = new EnhancementSessionRegistry();
```

Add imports:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
```

Update watch constructor:

```java
new WatchCommand(inst, transformer, config, jobManager, dispatcher, enhancementRegistry)
```

Update trace constructor:

```java
new TraceCommand(inst, transformer, config, jobManager, dispatcher, enhancementRegistry)
```

After dispatcher counts reach active state, add:

```java
awaitAtLeast("enhancement-watch", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.WATCH), 1, 5000);
awaitAtLeast("enhancement-trace", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.TRACE), 1, 5000);
```

After disconnect cleanup, add:

```java
awaitEquals("enhancement-watch", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.WATCH), 0, 5000);
awaitEquals("enhancement-trace", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.TRACE), 0, 5000);
```

- [ ] **Step 2: Run disconnect test and verify it fails**

Run:

```bash
mvn -pl core -Dtest=SessionCleanupOnDisconnectTest test
```

Expected: FAIL at compilation because watch/trace constructors do not accept `EnhancementSessionRegistry`.

- [ ] **Step 3: Add registry constructor dependency to `WatchCommand`**

Add imports:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionHandle;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
```

Add field:

```java
private final EnhancementSessionRegistry enhancementSessionRegistry;
```

Keep the existing 5-argument constructor and delegate to a new 6-argument constructor:

```java
public WatchCommand(
    Instrumentation instrumentation,
    SleuthClassFileTransformer transformer,
    ConfigView config,
    JobManager jobManager,
    SleuthSpyDispatcher spyDispatcher
) {
    this(instrumentation, transformer, config, jobManager, spyDispatcher, new EnhancementSessionRegistry());
}

public WatchCommand(
    Instrumentation instrumentation,
    SleuthClassFileTransformer transformer,
    ConfigView config,
    JobManager jobManager,
    SleuthSpyDispatcher spyDispatcher,
    EnhancementSessionRegistry enhancementSessionRegistry
) {
    this.instrumentation = instrumentation;
    this.transformer = transformer;
    this.config = config;
    if (jobManager == null) {
        throw new IllegalArgumentException("jobManager");
    }
    if (enhancementSessionRegistry == null) {
        throw new IllegalArgumentException("enhancementSessionRegistry");
    }
    this.jobManager = jobManager;
    this.spyDispatcher = spyDispatcher;
    this.enhancementSessionRegistry = enhancementSessionRegistry;
}
```

- [ ] **Step 4: Register watch handle and route cleanup through registry**

After `activeSessions.put(watchId, session);`, add:

```java
EnhancementSessionHandle handle = enhancementSessionRegistry.register(
    EnhancementSessionDescriptor.builder(watchId, EnhancementSessionKind.WATCH)
        .withClientId(currentClientId())
        .withClientSessionId(currentClientSessionId())
        .withCommandName("watch")
        .withClassPattern(targetClassName)
        .withMethodPattern(methodPattern)
        .withTargetClassNames(Collections.singletonList(targetClassName))
        .withLoaderIds(Collections.singletonList(resolved.getLoaderId()))
        .build(),
    reason -> stopWatch(watchId)
);
session.setHandle(handle);
```

Add helper methods:

```java
private String currentClientId() {
    CommandContext ctx = CommandContextHolder.get();
    return ctx != null ? ctx.getClientId() : null;
}

private String currentClientSessionId() {
    CommandContext ctx = CommandContextHolder.get();
    return ctx != null ? ctx.getSessionId() : null;
}
```

Change client cleanup registration:

```java
clientSession.registerCleanup(cleanupKey, () -> enhancementSessionRegistry.close(watchId, "client_disconnect"));
```

Change finally cleanup:

```java
enhancementSessionRegistry.close(watchId, "completed");
```

Change loop condition:

```java
while (!isWatchCancelled(watchId) && eventCount < maxCount) {
```

Add helper:

```java
private boolean isWatchCancelled(String watchId) {
    WatchSession session = activeSessions.get(watchId);
    return session == null || session.isCancelled();
}
```

Update `stopWatch` first lines:

```java
WatchSession session = activeSessions.remove(watchId);
if (session != null) {
    session.cancel();
    ...
}
```

Update `WatchSession` with cancellation and handle:

```java
private final AtomicBoolean cancelled = new AtomicBoolean(false);
private volatile EnhancementSessionHandle handle;

private void setHandle(EnhancementSessionHandle handle) {
    this.handle = handle;
}

private boolean isCancelled() {
    return cancelled.get();
}

private void cancel() {
    cancelled.set(true);
}
```

- [ ] **Step 5: Add registry constructor dependency to `TraceCommand`**

Apply the same constructor pattern as watch:

```java
private final EnhancementSessionRegistry enhancementSessionRegistry;
```

The 5-argument constructor delegates to:

```java
this(instrumentation, transformer, config, jobManager, spyDispatcher, new EnhancementSessionRegistry());
```

The 6-argument constructor validates and assigns `enhancementSessionRegistry`.

- [ ] **Step 6: Register trace handle and route cleanup through registry**

After `activeSessions.put(traceId, session);`, register:

```java
EnhancementSessionHandle handle = enhancementSessionRegistry.register(
    EnhancementSessionDescriptor.builder(traceId, EnhancementSessionKind.TRACE)
        .withClientId(currentClientId())
        .withClientSessionId(currentClientSessionId())
        .withCommandName("trace")
        .withClassPattern(targetClassName)
        .withMethodPattern(methodPattern)
        .withTargetClassNames(Collections.singletonList(targetClassName))
        .withLoaderIds(Collections.singletonList(resolved.getLoaderId()))
        .build(),
    reason -> stopTrace(traceId)
);
session.setHandle(handle);
```

Add `currentClientId()`, `currentClientSessionId()`, `isTraceCancelled(...)`, and cancellation fields on `TraceSession` using the same structure as watch.

Change trace loop:

```java
while (!isTraceCancelled(traceId) && invocationCount < maxCount) {
```

Change client cleanup:

```java
clientSession.registerCleanup(cleanupKey, () -> enhancementSessionRegistry.close(traceId, "client_disconnect"));
```

Change final cleanup:

```java
enhancementSessionRegistry.close(traceId, "completed");
```

- [ ] **Step 7: Update builtin constructors**

In `BuiltinCommandProvider`, update watch:

```java
new WatchCommand(
    instrumentation,
    context.requireTransformer(),
    context.requireConfig(),
    context.requireJobManager(),
    context.requireSpyDispatcher(),
    context.requireEnhancementSessionRegistry()
)
```

Update trace the same way.

- [ ] **Step 8: Update listener requirement test constructors**

In `ListenerModeRequirementTest`, create:

```java
EnhancementSessionRegistry enhancementRegistry = new EnhancementSessionRegistry();
```

Pass it to watch and trace constructors:

```java
new WatchCommand(inst, transformer, config, jobManager, dispatcher, enhancementRegistry)
new TraceCommand(inst, transformer, config, jobManager, dispatcher, enhancementRegistry)
```

- [ ] **Step 9: Run focused tests**

Run:

```bash
mvn -pl core -Dtest=SessionCleanupOnDisconnectTest,ListenerModeRequirementTest test
```

Expected: PASS.

- [ ] **Step 10: Commit watch and trace migration**

```bash
git add core/src/main/java/com/javasleuth/core/command/impl/WatchCommand.java core/src/main/java/com/javasleuth/core/command/impl/TraceCommand.java core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java core/src/test/java/com/javasleuth/command/ListenerModeRequirementTest.java
git commit -m "feat: register watch and trace enhancement sessions"
```

---

### Task 5: Migrate Monitor, Stack, and TT Sessions

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/StackCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/TtCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- Modify: `core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java`
- Modify: `core/src/test/java/com/javasleuth/command/ListenerModeRequirementTest.java`

- [ ] **Step 1: Extend tests for monitor, stack, and tt registry cleanup**

In `SessionCleanupOnDisconnectTest`, pass the existing `enhancementRegistry` to `TtCommand`:

```java
new TtCommand(inst, transformer, config, jobManager, dispatcher, enhancementRegistry)
```

Add assertions:

```java
awaitAtLeast("enhancement-tt", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.TT), 1, 5000);
...
awaitEquals("enhancement-tt", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.TT), 0, 5000);
```

Add separate tests for monitor and stack because the existing test currently starts watch/trace/tt together:

```java
@Test
public void testMonitorAndStackAreCleanedOnSessionClose() throws Exception {
    String clientId = "test-client-" + UUID.randomUUID();
    String clientInfo = "unit-test";
    String sessionId = "test-session-" + UUID.randomUUID();

    ClientSessionRegistry registry = new ClientSessionRegistry();
    Instrumentation inst = fakeInstrumentationWithLoadedClasses(DummyTarget.class);
    ProductionConfig config = ProductionConfig.createDefault();
    SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
    JobManager jobManager = new JobManager();
    SleuthSpyDispatcher dispatcher = new SleuthSpyDispatcher();
    EnhancementSessionRegistry enhancementRegistry = new EnhancementSessionRegistry();
    SleuthSpyAPI.setSpy(dispatcher);
    SleuthSpyAPI.init();

    Thread monitorThread = null;
    Thread stackThread = null;
    try {
        ClientSession session = registry.open(clientId, clientInfo, sessionId);
        CommandContext context = new CommandContext(clientId, clientInfo, sessionId, true, session);
        monitorThread = startInThreadWithContext(context, () -> {
            try {
                new MonitorCommand(inst, transformer, jobManager, dispatcher, enhancementRegistry)
                    .execute(new String[]{"monitor", DummyTarget.class.getName(), "doWork", "-n", "10", "-i", "1000"});
            } catch (Exception ignore) {
            }
        });
        stackThread = startInThreadWithContext(context, () -> {
            try {
                new StackCommand(inst, transformer, config, jobManager, dispatcher, enhancementRegistry)
                    .execute(new String[]{"stack", DummyTarget.class.getName(), "doWork", "-n", "10", "-t", "30"});
            } catch (Exception ignore) {
            }
        });

        awaitAtLeast("monitor", dispatcher::getActiveMonitorCount, 1, 5000);
        awaitAtLeast("stack", dispatcher::getActiveStackCount, 1, 5000);
        awaitAtLeast("enhancement-monitor", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.MONITOR), 1, 5000);
        awaitAtLeast("enhancement-stack", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.STACK), 1, 5000);

        registry.close(clientId, "disconnect");

        awaitEquals("monitor", dispatcher::getActiveMonitorCount, 0, 5000);
        awaitEquals("stack", dispatcher::getActiveStackCount, 0, 5000);
        awaitEquals("enhancement-monitor", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.MONITOR), 0, 5000);
        awaitEquals("enhancement-stack", () -> enhancementRegistry.countByKind().get(EnhancementSessionKind.STACK), 0, 5000);
    } finally {
        registry.close(clientId, "test_teardown_cleanup");
        SleuthSpyAPI.destroy();
        stopThread(monitorThread);
        stopThread(stackThread);
    }
}
```

- [ ] **Step 2: Run disconnect test and verify it fails**

Run:

```bash
mvn -pl core -Dtest=SessionCleanupOnDisconnectTest test
```

Expected: FAIL at compilation because monitor/stack/tt constructors do not accept the registry.

- [ ] **Step 3: Migrate `MonitorCommand`**

Add registry imports and field:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionHandle;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

private final EnhancementSessionRegistry enhancementSessionRegistry;
```

Add a 5-argument constructor:

```java
public MonitorCommand(
    Instrumentation instrumentation,
    SleuthClassFileTransformer transformer,
    JobManager jobManager,
    SleuthSpyDispatcher spyDispatcher,
    EnhancementSessionRegistry enhancementSessionRegistry
) {
    this.instrumentation = instrumentation;
    this.transformer = transformer;
    if (jobManager == null) {
        throw new IllegalArgumentException("jobManager");
    }
    if (enhancementSessionRegistry == null) {
        throw new IllegalArgumentException("enhancementSessionRegistry");
    }
    this.jobManager = jobManager;
    this.spyDispatcher = spyDispatcher;
    this.enhancementSessionRegistry = enhancementSessionRegistry;
}
```

Delegate existing 4-argument constructor to the new one with a new registry.

After `activeSessions.put(monitorId, session);`, register:

```java
EnhancementSessionHandle handle = enhancementSessionRegistry.register(
    EnhancementSessionDescriptor.builder(monitorId, EnhancementSessionKind.MONITOR)
        .withClientId(currentClientId())
        .withClientSessionId(currentClientSessionId())
        .withCommandName("monitor")
        .withClassPattern(classPattern)
        .withMethodPattern(methodPattern)
        .withTargetClassNames(session.classNames())
        .build(),
    reason -> stopMonitor(monitorId)
);
session.handle = handle;
```

Add `currentClientId()` and `currentClientSessionId()` helpers as in watch.

Update client cleanup:

```java
clientSession.registerCleanup(cleanupKey, () -> enhancementSessionRegistry.close(monitorId, "client_disconnect"));
```

Update final cleanup:

```java
enhancementSessionRegistry.close(monitorId, "completed");
```

Update the loop:

```java
for (int i = 0; i < rounds && !isMonitorCancelled(monitorId); i++) {
```

Add helper:

```java
private boolean isMonitorCancelled(String monitorId) {
    MonitorSession session = activeSessions.get(monitorId);
    return session == null || session.cancelled.get();
}
```

Add to `MonitorSession`:

```java
private final AtomicBoolean cancelled = new AtomicBoolean(false);
private volatile EnhancementSessionHandle handle;

private List<String> classNames() {
    List<String> names = new ArrayList<>();
    for (EnhancedClass ec : enhancedClasses) {
        if (ec != null && ec.clazz != null) {
            names.add(ec.clazz.getName());
        }
    }
    return names;
}
```

At the top of `stopMonitor`, after removing session:

```java
session.cancelled.set(true);
```

- [ ] **Step 4: Migrate `StackCommand` and `StackTraceLiteEngine`**

In `StackCommand`, add constructor overload accepting `EnhancementSessionRegistry` and pass it to `StackTraceLiteEngine`:

```java
this.traceEngine = new StackTraceLiteEngine(instrumentation, transformer, config, spyDispatcher, enhancementSessionRegistry);
```

In `StackTraceLiteEngine`, add registry field and constructor parameter:

```java
private final EnhancementSessionRegistry enhancementSessionRegistry;
```

After `activeSessions.put(stackId, new StackSession(...));`, keep a local `StackSession session`, put it, then register:

```java
EnhancementSessionHandle handle = enhancementSessionRegistry.register(
    EnhancementSessionDescriptor.builder(stackId, EnhancementSessionKind.STACK)
        .withClientId(currentClientId())
        .withClientSessionId(currentClientSessionId())
        .withCommandName("stack")
        .withClassPattern(classPattern)
        .withMethodPattern(methodPattern)
        .withTargetClassNames(Collections.singletonList(target.getName()))
        .build(),
    reason -> stop(stackId)
);
session.handle = handle;
```

Route client cleanup and final cleanup through:

```java
enhancementSessionRegistry.close(stackId, "client_disconnect");
enhancementSessionRegistry.close(stackId, "completed");
```

Change poll loop:

```java
while (!isCancelled(stackId) && events < maxCount) {
```

Add to `StackSession`:

```java
private final AtomicBoolean cancelled = new AtomicBoolean(false);
private volatile EnhancementSessionHandle handle;
```

Set `session.cancelled.set(true);` in `stop`.

- [ ] **Step 5: Migrate `TtCommand` and `TtRecordEngine`**

In `TtCommand`, add constructor overload accepting `EnhancementSessionRegistry` and pass it to `TtRecordEngine`:

```java
this.recordEngine = new TtRecordEngine(instrumentation, transformer, config, recordStore, spyDispatcher, enhancementSessionRegistry);
```

In `TtRecordEngine`, add registry field and constructor parameter.

After `activeSessions.put(ttId, session);`, register:

```java
EnhancementSessionHandle handle = enhancementSessionRegistry.register(
    EnhancementSessionDescriptor.builder(ttId, EnhancementSessionKind.TT)
        .withClientId(currentClientId())
        .withClientSessionId(currentClientSessionId())
        .withCommandName("tt")
        .withClassPattern(classPattern)
        .withMethodPattern(methodPattern)
        .withTargetClassNames(Collections.singletonList(target.getName()))
        .build(),
    reason -> stop(ttId)
);
session.handle = handle;
```

Route client cleanup and final cleanup through the registry.

Change poll loop:

```java
while (!isCancelled(ttId) && recorded < maxCount) {
```

Update `TtCommand.stop` to call the engine method that uses registry:

```java
boolean ok = recordEngine.stop(ttId);
```

Inside `TtRecordEngine.stop`, keep removing from `activeSessions` and closing listener/enhancer. Add cancellation state:

```java
session.cancelled.set(true);
```

- [ ] **Step 6: Update builtin and listener tests**

In `BuiltinCommandProvider`, pass `context.requireEnhancementSessionRegistry()` to monitor, stack, and tt constructors.

In `ListenerModeRequirementTest`, create one `EnhancementSessionRegistry` and pass it to monitor, stack, and tt constructors.

- [ ] **Step 7: Run focused tests**

Run:

```bash
mvn -pl core -Dtest=SessionCleanupOnDisconnectTest,ListenerModeRequirementTest test
```

Expected: PASS.

- [ ] **Step 8: Commit monitor/stack/tt migration**

```bash
git add core/src/main/java/com/javasleuth/core/command/impl/MonitorCommand.java core/src/main/java/com/javasleuth/core/command/impl/StackCommand.java core/src/main/java/com/javasleuth/core/command/impl/stack/StackTraceLiteEngine.java core/src/main/java/com/javasleuth/core/command/impl/TtCommand.java core/src/main/java/com/javasleuth/core/command/impl/tt/TtRecordEngine.java core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java core/src/test/java/com/javasleuth/command/SessionCleanupOnDisconnectTest.java core/src/test/java/com/javasleuth/command/ListenerModeRequirementTest.java
git commit -m "feat: register monitor stack and tt sessions"
```

---

### Task 6: Integrate VmTool Track Lifecycle

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java`
- Modify: `core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java`
- Modify: `core/src/test/java/com/javasleuth/command/ListenerModeRequirementTest.java`
- Modify: `core/src/test/java/com/javasleuth/command/EnhancementSessionResetTest.java`

- [ ] **Step 1: Add vmtool registry test coverage**

In `EnhancementSessionResetTest`, add:

```java
@Test
public void resetClosesVmToolEnhancementSessionHandle() {
    ProductionConfig config = ProductionConfig.createDefault();
    SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
    EnhancementSessionRegistry registry = new EnhancementSessionRegistry();
    AtomicInteger closed = new AtomicInteger(0);
    registry.register(
        EnhancementSessionDescriptor.builder("t-test", EnhancementSessionKind.VMTOOL)
            .withCommandName("vmtool")
            .build(),
        reason -> closed.incrementAndGet()
    );

    ResetCommand command = new ResetCommand(
        fakeInstrumentation(),
        transformer,
        new JobManager(),
        new VmToolSessionRegistry(new SleuthSpyDispatcher()),
        registry
    );

    String output = command.execute(new String[] {"reset"});

    Assert.assertTrue(output.contains("enhancementSessions=1"));
    Assert.assertEquals(1, closed.get());
    Assert.assertEquals(0, registry.size());
}
```

- [ ] **Step 2: Run vmtool-related tests**

Run:

```bash
mvn -pl core -Dtest=EnhancementSessionResetTest,ListenerModeRequirementTest test
```

Expected: PASS for reset handle coverage and existing vmtool listener requirement coverage. Constructor changes happen in the next step.

- [ ] **Step 3: Add registry dependency to `VmToolCommand`**

Add imports:

```java
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import java.util.ArrayList;
import java.util.List;
```

Add field:

```java
private final EnhancementSessionRegistry enhancementSessionRegistry;
```

Extend constructor:

```java
public VmToolCommand(
    Instrumentation instrumentation,
    SleuthClassFileTransformer transformer,
    ConfigView config,
    DangerousCommandConfirmationManager dangerousConfirm,
    VmToolSessionRegistry registry,
    EnhancementSessionRegistry enhancementSessionRegistry
) {
    this.instrumentation = instrumentation;
    this.transformer = transformer;
    this.config = config;
    this.dangerousConfirm = dangerousConfirm;
    if (registry == null) {
        throw new IllegalArgumentException("registry");
    }
    if (enhancementSessionRegistry == null) {
        throw new IllegalArgumentException("enhancementSessionRegistry");
    }
    this.registry = registry;
    this.enhancementSessionRegistry = enhancementSessionRegistry;
}
```

Keep existing constructor by delegating with `new EnhancementSessionRegistry()` for tests that instantiate vmtool directly.

- [ ] **Step 4: Register vmtool handle after successful track start**

In `handleTrack`, after successful `StartResult`, add:

```java
VmToolSessionRegistry.TrackSession session = r.getSession();
String trackId = session.getId();
enhancementSessionRegistry.register(
    EnhancementSessionDescriptor.builder(trackId, EnhancementSessionKind.VMTOOL)
        .withClientId(currentClientId())
        .withClientSessionId(currentClientSessionId())
        .withCommandName("vmtool")
        .withClassPattern(session.getBaseClassName())
        .withTargetClassNames(vmtoolTargetClassNames(session))
        .withLoaderIds(vmtoolLoaderIds(session))
        .withDetails("includeSubclasses=" + session.isIncludeSubclasses() + ", maxEntries=" + session.getMaxEntries())
        .build(),
    reason -> registry.stopTrack(instrumentation, transformer, trackId)
);
```

Add helpers:

```java
private String currentClientId() {
    CommandContext ctx = CommandContextHolder.get();
    return ctx != null ? ctx.getClientId() : null;
}

private String currentClientSessionId() {
    CommandContext ctx = CommandContextHolder.get();
    return ctx != null ? ctx.getSessionId() : null;
}

private List<String> vmtoolTargetClassNames(VmToolSessionRegistry.TrackSession session) {
    List<String> names = new ArrayList<>();
    for (VmToolSessionRegistry.EnhancedClass ec : session.getEnhancedClasses()) {
        if (ec != null) {
            names.add(ec.getClassName());
        }
    }
    return names;
}

private List<Integer> vmtoolLoaderIds(VmToolSessionRegistry.TrackSession session) {
    List<Integer> ids = new ArrayList<>();
    for (VmToolSessionRegistry.EnhancedClass ec : session.getEnhancedClasses()) {
        if (ec != null) {
            ids.add(ec.getLoaderId());
        }
    }
    return ids;
}
```

Change vmtool client cleanup registration:

```java
clientSession.registerCleanup(cleanupKey, () -> enhancementSessionRegistry.close(trackId, "client_disconnect"));
```

- [ ] **Step 5: Change `vmtool stop` to close registry first**

In `handleStop`, after cleanup removal:

```java
boolean closedByRegistry = enhancementSessionRegistry.close(trackId, "vmtool_stop");
if (closedByRegistry) {
    return "vmtool track stopped. id=" + trackId.trim();
}
return registry.stopTrack(instrumentation, transformer, trackId).getMessage();
```

This keeps fallback behavior for tracks created before the registry handle existed.

- [ ] **Step 6: Update builtin vmtool constructor**

In `BuiltinCommandProvider`, update vmtool:

```java
new VmToolCommand(
    instrumentation,
    context.requireTransformer(),
    context.requireConfig(),
    context.requireDangerousConfirm(),
    context.requireVmToolSessionRegistry(),
    context.requireEnhancementSessionRegistry()
)
```

- [ ] **Step 7: Run vmtool focused tests**

Run:

```bash
mvn -pl core -Dtest=ListenerModeRequirementTest,EnhancementSessionResetTest test
```

Expected: PASS.

- [ ] **Step 8: Commit vmtool integration**

```bash
git add core/src/main/java/com/javasleuth/core/command/impl/VmToolCommand.java core/src/main/java/com/javasleuth/core/command/BuiltinCommandProvider.java core/src/test/java/com/javasleuth/command/ListenerModeRequirementTest.java core/src/test/java/com/javasleuth/command/EnhancementSessionResetTest.java
git commit -m "feat: register vmtool enhancement sessions"
```

---

### Task 7: Runtime Close Ordering and Documentation

**Files:**
- Modify: `core/src/main/java/com/javasleuth/core/agent/runtime/AttachSessionContext.java`
- Modify: `docs/tutorial/command-instrumentation-and-rollback.md`
- Modify: `docs/usage/commands.md`
- Modify: `docs/usage/troubleshooting.md`

- [ ] **Step 1: Add close ordering assertion**

In `AttachSessionContextTest.close_clearsEnhancementSessionRegistry`, change the registered closer to assert it runs:

```java
AtomicInteger closed = new AtomicInteger(0);
session.getEnhancementSessionRegistry().register(
    com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor
        .builder("attach-close-watch", com.javasleuth.core.enhancement.session.EnhancementSessionKind.WATCH)
        .build(),
    reason -> closed.incrementAndGet()
);
...
Assert.assertEquals(1, closed.get());
```

- [ ] **Step 2: Run attach test**

Run:

```bash
mvn -pl core -Dtest=AttachSessionContextTest test
```

Expected: PASS.

- [ ] **Step 3: Confirm close ordering in `AttachSessionContext`**

Ensure `close()` snapshots enhanced classes, shuts down command processor, and then calls:

```java
shutdownAttachResourcesBestEffort(inst, tx, jobManager, clientSessionRegistry, vmToolSessionRegistry, enhancementSessionRegistry);
removeEnhancersBestEffort(tx);
rollbackEnhancedClassesBestEffort(inst, enhanced);
```

Ensure `shutdownAttachResourcesBestEffort` order is:

```java
shutdownEnhancementSessionsBestEffort(enhancementSessionRegistry, "shutdown");
shutdownJobManagerBestEffort(jobManager, "shutdown");
shutdownClientSessionsBestEffort(clientSessionRegistry, "shutdown");
shutdownVmToolSessionsBestEffort(instrumentation, transformer, vmToolSessionRegistry, "shutdown");
AgentGlobalState.resetBootstrapAttachStateBestEffort();
```

- [ ] **Step 4: Update tutorial documentation**

In `docs/tutorial/command-instrumentation-and-rollback.md`, update the reset section to state:

```markdown
`reset` first closes all attach-scope enhancement session handles. These handles are registered by
`watch`, `trace`, `monitor`, `stack`, `tt`, and `vmtool track` after successful listener/enhancer/retransform setup.
Each handle owns command-specific cleanup: cancel the collection loop, remove enhancers, retransform target classes,
and unregister listeners. After that explicit close path, `reset` still runs transformer and bootstrap-interceptor
fallback cleanup.
```

Update stop/detach text to state:

```markdown
`stop` reaches the same attach close path used by detach. That path closes enhancement sessions before broad
transformer fallback cleanup, then stops jobs/client sessions and clears dispatcher/bootstrap state.
```

- [ ] **Step 5: Update command usage docs**

In `docs/usage/commands.md`, update `status` output description with:

```markdown
`status` includes an `Enhancement Sessions` section. These counts represent command-owned active enhancement
sessions by kind (`Watch`, `Trace`, `Monitor`, `Stack`, `TT`, `VmTool`, `Other`). Dispatcher listener counts remain
visible separately and describe the lower-level listener runtime state.
```

Update `reset` description with:

```markdown
`reset` closes active enhancement sessions first, then applies transformer/interceptor fallback cleanup. The summary
includes `enhancementSessions`, `enhancementClosed`, and `enhancementFailed`.
```

- [ ] **Step 6: Update troubleshooting docs**

In `docs/usage/troubleshooting.md`, add a cleanup troubleshooting note:

```markdown
If `reset` reports `enhancementFailed` greater than zero, inspect the agent log for the failed session id. The reset
command still runs fallback cleanup, so bytecode enhancers should be removed best-effort even when a session-specific
close callback fails. Run `status` after reset and verify `Enhancement Sessions -> Active Sessions` is `0`.
```

- [ ] **Step 7: Run focused lifecycle and docs-neutral tests**

Run:

```bash
mvn -pl core -Dtest=AttachSessionContextTest,EnhancementSessionResetTest test
```

Expected: PASS.

- [ ] **Step 8: Commit runtime close and docs**

```bash
git add core/src/main/java/com/javasleuth/core/agent/runtime/AttachSessionContext.java core/src/test/java/com/javasleuth/core/agent/runtime/AttachSessionContextTest.java docs/tutorial/command-instrumentation-and-rollback.md docs/usage/commands.md docs/usage/troubleshooting.md
git commit -m "docs: describe enhancement session lifecycle"
```

---

### Task 8: Regression Sweep and Full Verification

**Files:**
- Read: all modified files from Tasks 1-7
- Modify: only files needed to fix compile or test failures found by this task

- [ ] **Step 1: Search for stale direct cleanup patterns**

Run:

```bash
rg -n "registerCleanup\\(|activeSessions|stopWatch\\(|stopTrace\\(|stopMonitor\\(|stop\\(ttId\\)|stop\\(stackId\\)|stopTrack\\(" core/src/main/java/com/javasleuth/core -g '*.java'
```

Expected: Each instrumentation command may keep private cleanup methods and local maps, but active enhancement sessions must register an `EnhancementSessionRegistry` handle and client cleanup must close through `enhancementSessionRegistry.close(...)`.

- [ ] **Step 2: Run focused command tests**

Run:

```bash
mvn -pl core -Dtest=EnhancementSessionRegistryTest,SessionCleanupOnDisconnectTest,EnhancementSessionResetTest,AttachSessionContextTest,ListenerModeRequirementTest test
```

Expected: PASS.

- [ ] **Step 3: Run core test suite**

Run:

```bash
mvn -pl core test
```

Expected: PASS.

- [ ] **Step 4: Run dependent-module core suite if `-pl core` fails from missing reactor dependencies**

Run only if Step 3 fails because Maven cannot resolve reactor modules:

```bash
mvn -pl core -am test
```

Expected: PASS.

- [ ] **Step 5: Inspect reset/status output strings**

Run:

```bash
rg -n "enhancementSessions|Enhancement Sessions|enhancementClosed|enhancementFailed" core/src/main/java core/src/test/java docs
```

Expected: Matches appear in `ResetCommand`, `StatusCommand`, tests, and docs.

- [ ] **Step 6: Commit regression fixes**

If Step 1-5 required code or doc changes, commit them:

```bash
git add core/src/main/java core/src/test/java docs
git commit -m "test: verify enhancement session lifecycle"
```

If Step 1-5 required no changes, do not create an empty commit.
