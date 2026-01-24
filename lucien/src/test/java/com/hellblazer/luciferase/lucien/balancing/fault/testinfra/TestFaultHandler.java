package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import com.hellblazer.luciferase.lucien.balancing.fault.SimpleFaultHandler;

/**
 * Test double for fault handler in integration tests.
 * <p>
 * Extends SimpleFaultHandler to provide controlled fault injection for testing.
 * SimpleFaultHandler already provides complete FaultHandler functionality.
 */
public class TestFaultHandler extends SimpleFaultHandler {

    /**
     * Create test fault handler with default configuration.
     */
    public TestFaultHandler() {
        super(com.hellblazer.luciferase.lucien.balancing.fault.FaultConfiguration.defaultConfig());
    }

    /**
     * Manually inject partition failure for testing.
     * <p>
     * Reports barrier timeout which will trigger fault detection.
     *
     * @param partitionId partition UUID to mark as failed
     */
    public void injectFailure(java.util.UUID partitionId) {
        reportBarrierTimeout(partitionId);
    }
}
