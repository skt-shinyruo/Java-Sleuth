package com.javasleuth.config;

/**
 * Represents a runtime override change (auditable record).
 *
 * <p>For sensitive keys, old/new values are stored as masked summaries only.</p>
 */
public final class ConfigChange {
    private final long sequence;
    private final String key;
    private final String oldValueSummary;
    private final String newValueSummary;
    private final ConfigUpdateSource source;
    private final long timestampMs;

    public ConfigChange(
        long sequence,
        String key,
        String oldValueSummary,
        String newValueSummary,
        ConfigUpdateSource source,
        long timestampMs
    ) {
        this.sequence = sequence;
        this.key = key;
        this.oldValueSummary = oldValueSummary;
        this.newValueSummary = newValueSummary;
        this.source = source;
        this.timestampMs = timestampMs;
    }

    public long getSequence() {
        return sequence;
    }

    public String getKey() {
        return key;
    }

    public String getOldValueSummary() {
        return oldValueSummary;
    }

    public String getNewValueSummary() {
        return newValueSummary;
    }

    public ConfigUpdateSource getSource() {
        return source;
    }

    public long getTimestampMs() {
        return timestampMs;
    }
}

