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

    public String getSessionId() {
        return sessionId;
    }

    public EnhancementSessionKind getKind() {
        return kind;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSessionId() {
        return clientSessionId;
    }

    public String getCommandName() {
        return commandName;
    }

    public String getClassPattern() {
        return classPattern;
    }

    public String getMethodPattern() {
        return methodPattern;
    }

    public List<String> getTargetClassNames() {
        return targetClassNames;
    }

    public List<Integer> getLoaderIds() {
        return loaderIds;
    }

    public String getBackgroundJobId() {
        return backgroundJobId;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public String getDetails() {
        return details;
    }

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
