package com.javasleuth.test;

import com.javasleuth.core.agent.runtime.AgentGlobalState;

/**
 * Test-only global state reset helper.
 *
 * <p>Purpose: reduce cross-test pollution from static singletons / registries, especially when tests
 * involve lifecycle close/shutdown code paths.</p>
 */
public final class SleuthTestState {
    private SleuthTestState() {}

    public static void resetAll(String reason) {
        String r = reason != null ? reason : "test_reset";

        // Bootstrap-side registries (spy/bridge layer)
        AgentGlobalState.resetBootstrapAttachStateBestEffort();
    }
}
