package com.javasleuth.core.spy.listener;

import com.javasleuth.core.spy.SleuthAdviceListener;
import com.javasleuth.core.vmtool.VmToolTracker;

/**
 * Per-track vmtool listener: receives constructor-return events and records the instance into the core tracker.
 */
public final class VmToolTrackAdviceListener implements SleuthAdviceListener {
    private final String trackId;
    private final VmToolTracker tracker;

    public VmToolTrackAdviceListener(String trackId, VmToolTracker tracker) {
        if (trackId == null || trackId.trim().isEmpty()) {
            throw new IllegalArgumentException("trackId");
        }
        if (tracker == null) {
            throw new IllegalArgumentException("tracker");
        }
        this.trackId = trackId.trim();
        this.tracker = tracker;
    }

    @Override
    public void onConstructed(Object instance) {
        tracker.onConstructed(trackId, instance);
    }
}

