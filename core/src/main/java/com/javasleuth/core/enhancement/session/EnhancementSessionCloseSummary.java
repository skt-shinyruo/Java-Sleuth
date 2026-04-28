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

    public int getTotal() {
        return total;
    }

    public int getClosed() {
        return closed;
    }

    public int getMissing() {
        return missing;
    }

    public int getFailed() {
        return failed;
    }

    public Map<String, String> getFailureMessages() {
        return failureMessages;
    }
}
