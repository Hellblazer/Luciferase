package com.hellblazer.luciferase.lucien.balancing.fault.testinfra;

import com.hellblazer.luciferase.simulation.distributed.integration.TestClock;

import java.util.*;

/**
 * Executable fault injection scenario.
 * <p>
 * Created by {@link FaultScenarioBuilder}, contains a timeline of scheduled
 * actions to execute during scenario execution.
 * <p>
 * <b>Execution Model</b>:
 * <ol>
 *   <li>Set initial clock time (t=0)</li>
 *   <li>Execute all actions scheduled at each time point</li>
 *   <li>Advance clock to next scheduled time</li>
 *   <li>Repeat until all actions complete</li>
 * </ol>
 */
public class FaultScenario {

    private final String name;
    private final int partitionCount;
    private final Map<Long, List<Runnable>> scheduledActions;
    private final FaultInjector injector;
    private final TestClock clock;

    /**
     * Package-private constructor - use {@link FaultScenarioBuilder}.
     */
    FaultScenario(
        String name,
        int partitionCount,
        Map<Long, List<Runnable>> scheduledActions,
        FaultInjector injector,
        TestClock clock
    ) {
        this.name = name;
        this.partitionCount = partitionCount;
        this.scheduledActions = new TreeMap<>(scheduledActions);
        this.injector = injector;
        this.clock = clock;
    }

    /**
     * Execute scenario synchronously.
     * <p>
     * Runs all scheduled actions in temporal order. Advances test clock
     * between time points.
     *
     * @throws Exception if any action throws
     */
    public void execute() throws Exception {
        // Reset clock to t=0
        clock.setTime(0);

        // Execute actions in temporal order
        for (var entry : scheduledActions.entrySet()) {
            var timeMs = entry.getKey();
            var actions = entry.getValue();

            // Advance clock to scheduled time
            if (timeMs > clock.currentTimeMillis()) {
                var delta = timeMs - clock.currentTimeMillis();
                clock.advance(delta);
            }

            // Execute all actions at this time
            for (var action : actions) {
                try {
                    action.run();
                } catch (Exception e) {
                    throw new RuntimeException(
                        "Scenario '" + name + "' action failed at t=" + timeMs + "ms", e);
                }
            }
        }
    }

    /**
     * Get scenario name.
     *
     * @return scenario name
     */
    public String getName() {
        return name;
    }

    /**
     * Get partition count.
     *
     * @return partition count
     */
    public int getPartitionCount() {
        return partitionCount;
    }

    /**
     * Get scheduled action count.
     *
     * @return total number of scheduled actions
     */
    public int getActionCount() {
        return scheduledActions.values().stream()
            .mapToInt(List::size)
            .sum();
    }

    /**
     * Get scenario duration (time of last scheduled action).
     *
     * @return duration in milliseconds
     */
    public long getDurationMs() {
        if (scheduledActions.isEmpty()) {
            return 0;
        }
        return Collections.max(scheduledActions.keySet());
    }

    @Override
    public String toString() {
        return String.format("FaultScenario[name='%s', partitions=%d, actions=%d, duration=%dms]",
            name, partitionCount, getActionCount(), getDurationMs());
    }
}
