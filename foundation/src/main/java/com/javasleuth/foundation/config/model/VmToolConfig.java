package com.javasleuth.foundation.config.model;

public final class VmToolConfig {
    private final int trackMaxEntries;
    private final int trackClassLimit;

    public VmToolConfig(int trackMaxEntries, int trackClassLimit) {
        this.trackMaxEntries = trackMaxEntries;
        this.trackClassLimit = trackClassLimit;
    }

    public int getTrackMaxEntries() {
        return trackMaxEntries;
    }

    public int getTrackClassLimit() {
        return trackClassLimit;
    }
}
