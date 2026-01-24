package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

/**
 * @deprecated Use {@link FaultInjector} instead.
 * <p>
 * This class has been renamed to avoid conflict with the existing
 * FaultSimulator in the parent package.
 */
@Deprecated(since = "P5.1", forRemoval = true)
public class FaultSimulator extends FaultInjector {
    /**
     * @deprecated Use {@link FaultInjector} constructor instead.
     */
    @Deprecated(since = "P5.1", forRemoval = true)
    public FaultSimulator(TestClock clock) {
        super(clock);
    }
}
