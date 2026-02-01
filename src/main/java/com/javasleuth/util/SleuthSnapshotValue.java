package com.javasleuth.util;

/**
 * A lightweight, non-referential snapshot wrapper used to avoid retaining large object graphs.
 *
 * <p>It intentionally stores only a pre-formatted summary string.
 */
public final class SleuthSnapshotValue {
    private final String summary;

    public SleuthSnapshotValue(String summary) {
        this.summary = summary;
    }

    public String getSummary() {
        return summary;
    }

    @Override
    public String toString() {
        return summary;
    }
}

