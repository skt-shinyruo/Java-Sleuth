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

    public String getSessionId() {
        return descriptor.getSessionId();
    }

    public EnhancementSessionKind getKind() {
        return descriptor.getKind();
    }

    public String getClientId() {
        return descriptor.getClientId();
    }

    public String getClientSessionId() {
        return descriptor.getClientSessionId();
    }

    public String getCommandName() {
        return descriptor.getCommandName();
    }

    public String getClassPattern() {
        return descriptor.getClassPattern();
    }

    public String getMethodPattern() {
        return descriptor.getMethodPattern();
    }

    public List<String> getTargetClassNames() {
        return descriptor.getTargetClassNames();
    }

    public List<Integer> getLoaderIds() {
        return descriptor.getLoaderIds();
    }

    public String getBackgroundJobId() {
        return descriptor.getBackgroundJobId();
    }

    public long getCreatedAtMs() {
        return descriptor.getCreatedAtMs();
    }

    public String getDetails() {
        return descriptor.getDetails();
    }
}
